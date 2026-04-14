"""
Rappelo Bridge — SIP Admin Panel
FastAPI app providing REST API + web dashboard for Kamailio management.
"""
import hashlib
import os
import re
from contextlib import asynccontextmanager
from datetime import datetime

import asyncpg
import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, field_validator

# ── Config ──────────────────────────────────────────────
DB_HOST = os.getenv("DB_HOST", "postgres")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "kamailio")
DB_USER = os.getenv("DB_USER", "kamailio")
DB_PASS = os.getenv("DB_PASS", "kamailio-secret-change-me")
SIP_DOMAIN = os.getenv("SIP_DOMAIN", "sip.rappelo.local")
KAMAILIO_JSONRPC = os.getenv("KAMAILIO_JSONRPC", "http://kamailio:5060/jsonrpc")

pool: asyncpg.Pool | None = None


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(
        host=DB_HOST, port=DB_PORT, database=DB_NAME,
        user=DB_USER, password=DB_PASS, min_size=2, max_size=10,
    )
    yield
    if pool:
        await pool.close()


app = FastAPI(title="Rappelo SIP Admin", lifespan=lifespan)
templates = Jinja2Templates(directory=os.path.join(os.path.dirname(__file__), "templates"))

# ── Validators ──────────────────────────────────────────
USERNAME_RE = re.compile(r"^[a-zA-Z0-9._-]{1,64}$")
DOMAIN_RE = re.compile(r"^[a-zA-Z0-9._-]{1,64}$")


class AccountCreate(BaseModel):
    username: str
    password: str
    domain: str | None = None

    @field_validator("username")
    @classmethod
    def valid_username(cls, v: str) -> str:
        if not USERNAME_RE.match(v):
            raise ValueError("username must be 1-64 alphanumeric/._- chars")
        return v

    @field_validator("password")
    @classmethod
    def valid_password(cls, v: str) -> str:
        if len(v) < 6 or len(v) > 128:
            raise ValueError("password must be 6-128 chars")
        return v

    @field_validator("domain")
    @classmethod
    def valid_domain(cls, v: str | None) -> str | None:
        if v and not DOMAIN_RE.match(v):
            raise ValueError("invalid domain")
        return v


class PasswordChange(BaseModel):
    password: str

    @field_validator("password")
    @classmethod
    def valid_password(cls, v: str) -> str:
        if len(v) < 6 or len(v) > 128:
            raise ValueError("password must be 6-128 chars")
        return v


# ── Helpers ─────────────────────────────────────────────
def ha1(user: str, domain: str, password: str) -> str:
    return hashlib.md5(f"{user}:{domain}:{password}".encode()).hexdigest()


def ha1b(user: str, domain: str, password: str) -> str:
    return hashlib.md5(f"{user}@{domain}:{domain}:{password}".encode()).hexdigest()


async def kamailio_rpc(method: str, params: list | None = None) -> dict:
    """Call Kamailio JSONRPC."""
    payload = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params:
        payload["params"] = params
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            r = await client.post(KAMAILIO_JSONRPC, json=payload)
            return r.json()
    except Exception as e:
        return {"error": str(e)}


# ── Dashboard ───────────────────────────────────────────
@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    return templates.TemplateResponse("index.html", {
        "request": request,
        "sip_domain": SIP_DOMAIN,
    })


# ── Account CRUD ────────────────────────────────────────
@app.get("/api/accounts")
async def list_accounts():
    rows = await pool.fetch(
        "SELECT id, username, domain, datetime_created FROM subscriber ORDER BY username"
    )
    return [dict(r) for r in rows]


@app.post("/api/accounts", status_code=201)
async def create_account(body: AccountCreate):
    domain = body.domain or SIP_DOMAIN
    h1 = ha1(body.username, domain, body.password)
    h1b = ha1b(body.username, domain, body.password)
    try:
        await pool.execute(
            """INSERT INTO subscriber (username, domain, ha1, ha1b, datetime_created)
               VALUES ($1, $2, $3, $4, NOW())""",
            body.username, domain, h1, h1b,
        )
    except asyncpg.UniqueViolationError:
        raise HTTPException(409, "Account already exists")
    return {"sip_uri": f"sip:{body.username}@{domain}", "username": body.username, "domain": domain}


@app.put("/api/accounts/{username}/password")
async def change_password(username: str, body: PasswordChange, domain: str | None = None):
    domain = domain or SIP_DOMAIN
    if not USERNAME_RE.match(username):
        raise HTTPException(400, "Invalid username")
    h1 = ha1(username, domain, body.password)
    h1b = ha1b(username, domain, body.password)
    result = await pool.execute(
        "UPDATE subscriber SET ha1=$1, ha1b=$2 WHERE username=$3 AND domain=$4",
        h1, h1b, username, domain,
    )
    if result == "UPDATE 0":
        raise HTTPException(404, "Account not found")
    return {"status": "updated"}


@app.delete("/api/accounts/{username}")
async def delete_account(username: str, domain: str | None = None):
    domain = domain or SIP_DOMAIN
    if not USERNAME_RE.match(username):
        raise HTTPException(400, "Invalid username")
    result = await pool.execute(
        "DELETE FROM subscriber WHERE username=$1 AND domain=$2",
        username, domain,
    )
    if result == "DELETE 0":
        raise HTTPException(404, "Account not found")
    return {"status": "deleted"}


# ── Registrations ───────────────────────────────────────
@app.get("/api/registrations")
async def list_registrations():
    rows = await pool.fetch(
        """SELECT username, domain, contact, user_agent,
                  expires, last_modified, socket
           FROM location WHERE expires > NOW() ORDER BY username"""
    )
    return [dict(r) for r in rows]


# ── CDR ─────────────────────────────────────────────────
@app.get("/api/cdr")
async def list_cdr(limit: int = 50):
    limit = min(max(limit, 1), 500)
    rows = await pool.fetch(
        """SELECT time, method, src_user, src_domain, dst_user, dst_domain,
                  sip_code, sip_reason
           FROM acc ORDER BY time DESC LIMIT $1""",
        limit,
    )
    return [dict(r) for r in rows]


@app.get("/api/missed")
async def list_missed(limit: int = 50):
    limit = min(max(limit, 1), 500)
    rows = await pool.fetch(
        "SELECT time, src_user, dst_user, sip_code FROM missed_calls ORDER BY time DESC LIMIT $1",
        limit,
    )
    return [dict(r) for r in rows]


# ── System Status ───────────────────────────────────────
@app.get("/api/status")
async def system_status():
    # Grab stats from Kamailio JSONRPC
    stats = await kamailio_rpc("stats.get_statistics", ["all"])
    dialogs = await kamailio_rpc("dlg.stats_active")

    # DB counts
    accounts = await pool.fetchval("SELECT COUNT(*) FROM subscriber")
    online = await pool.fetchval("SELECT COUNT(*) FROM location WHERE expires > NOW()")
    total_calls = await pool.fetchval("SELECT COUNT(*) FROM acc")

    return {
        "accounts": accounts,
        "online": online,
        "total_calls": total_calls,
        "kamailio_stats": stats.get("result", stats.get("error", "unavailable")),
        "active_dialogs": dialogs.get("result", dialogs.get("error", "unavailable")),
        "sip_domain": SIP_DOMAIN,
        "timestamp": datetime.utcnow().isoformat(),
    }


# ── Kamailio RPC proxy ─────────────────────────────────
@app.get("/api/kamailio/{method}")
async def kamailio_proxy(method: str):
    """Proxy safe read-only Kamailio JSONRPC calls."""
    allowed = {"core.version", "core.uptime", "stats.get_statistics",
               "ul.dump", "dlg.list", "dlg.stats_active", "tm.stats"}
    if method not in allowed:
        raise HTTPException(403, f"RPC method '{method}' not allowed")
    result = await kamailio_rpc(method, ["all"] if method == "stats.get_statistics" else None)
    return result
