"""JWT verification and branch scoping.

Contract with the Java core service (which issues the tokens):

* ``Authorization: Bearer <JWT>``; HS256 signed with the shared ``JWT_SECRET`` (raw UTF-8 bytes).
* ``iss`` == ``JWT_ISSUER`` (default ``gymops-core``), ``aud`` == ``gymops-api``, ``typ`` == ``access``.
* Claims: ``sub`` (user id), ``email``, ``roles`` (list[str]), ``branches`` (list[int]), ``exp``, ``iat``.

Authorisation: SUPER_ADMIN and GYM_ADMIN see every branch; MANAGER only the branches in the
``branches`` claim. Every other role receives 403.
"""

from __future__ import annotations

from dataclasses import dataclass

import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings, get_settings
from app.core.errors import AccessDeniedError, UnauthorizedError

ADMIN_ROLES = frozenset({"SUPER_ADMIN", "GYM_ADMIN"})
MANAGER_ROLE = "MANAGER"
ANALYTICS_ROLES = ADMIN_ROLES | {MANAGER_ROLE}
ACCESS_TOKEN_TYPE = "access"

_bearer = HTTPBearer(auto_error=False, description="Access token issued by the GymOps core service")


@dataclass(frozen=True)
class Principal:
    user_id: str
    email: str | None
    roles: frozenset[str]
    branches: frozenset[int]

    @property
    def is_admin(self) -> bool:
        return bool(self.roles & ADMIN_ROLES)


@dataclass(frozen=True)
class BranchScope:
    """Resolved data scope for a request.

    ``branch_ids is None`` means "all branches" (organisation-wide admin without a branch filter).
    An empty tuple means "no branches" (e.g. a manager not assigned to any branch).
    """

    branch_ids: tuple[int, ...] | None
    requested_branch_id: int | None = None

    @property
    def is_single_branch(self) -> bool:
        return self.branch_ids is not None and len(self.branch_ids) == 1

    def cache_key(self) -> tuple:
        return ("ALL",) if self.branch_ids is None else self.branch_ids


def _normalise_role(role: str) -> str:
    role = str(role).strip().upper()
    return role[5:] if role.startswith("ROLE_") else role


def decode_access_token(token: str, settings: Settings) -> Principal:
    try:
        claims = jwt.decode(
            token,
            key=settings.jwt_secret,
            algorithms=["HS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["exp", "iat", "sub", "iss", "aud"]},
            leeway=30,
        )
    except jwt.ExpiredSignatureError as exc:
        raise UnauthorizedError("Access token has expired") from exc
    except jwt.InvalidTokenError as exc:
        raise UnauthorizedError("Invalid access token") from exc

    if claims.get("typ") != ACCESS_TOKEN_TYPE:
        raise UnauthorizedError("Invalid token type")

    raw_roles = claims.get("roles") or []
    raw_branches = claims.get("branches") or []
    if not isinstance(raw_roles, list) or not isinstance(raw_branches, list):
        raise UnauthorizedError("Malformed token claims")
    try:
        branches = frozenset(int(b) for b in raw_branches)
    except (TypeError, ValueError) as exc:
        raise UnauthorizedError("Malformed token claims") from exc

    return Principal(
        user_id=str(claims["sub"]),
        email=claims.get("email"),
        roles=frozenset(_normalise_role(r) for r in raw_roles),
        branches=branches,
    )


def get_current_principal(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> Principal:
    if credentials is None or credentials.scheme.lower() != "bearer" or not credentials.credentials:
        raise UnauthorizedError("Missing bearer token")
    principal = decode_access_token(credentials.credentials, settings)
    request.state.user_id = principal.user_id
    return principal


def require_analytics_access(principal: Principal = Depends(get_current_principal)) -> Principal:
    if not principal.roles & ANALYTICS_ROLES:
        raise AccessDeniedError("Analytics are available to SUPER_ADMIN, GYM_ADMIN and MANAGER roles only")
    return principal


def resolve_scope(principal: Principal, branch_id: int | None) -> BranchScope:
    """Translate the optional ``branchId`` parameter into the set of branches the caller may see."""
    if principal.is_admin:
        return BranchScope(
            branch_ids=None if branch_id is None else (branch_id,), requested_branch_id=branch_id
        )
    if branch_id is not None:
        if branch_id not in principal.branches:
            raise AccessDeniedError(f"You are not assigned to branch {branch_id}")
        return BranchScope(branch_ids=(branch_id,), requested_branch_id=branch_id)
    return BranchScope(branch_ids=tuple(sorted(principal.branches)), requested_branch_id=None)
