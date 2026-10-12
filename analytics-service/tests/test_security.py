import pytest
from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AccessDeniedError
from app.core.security import Principal, resolve_scope
from tests.conftest import auth, mint_token

URL = "/analytics/attendance"


def _assert_error(resp, status, code):
    assert resp.status_code == status, resp.text
    body = resp.json()
    assert body["status"] == status and body["code"] == code
    assert set(body) == {"timestamp", "status", "error", "code", "message", "path", "correlationId"}
    assert body["path"] == URL
    return body


def test_missing_token_401(client):
    resp = client.get(URL)
    body = _assert_error(resp, 401, "UNAUTHORIZED")
    assert body["error"] == "Unauthorized"
    assert resp.headers["www-authenticate"] == "Bearer"


def test_non_bearer_scheme_401(client):
    _assert_error(client.get(URL, headers={"Authorization": "Basic dXNlcjpwYXNz"}), 401, "UNAUTHORIZED")


def test_wrong_signature_401(client):
    resp = client.get(URL, headers=auth(secret="another-secret-that-is-long-enough-123456"))
    _assert_error(resp, 401, "UNAUTHORIZED")


def test_expired_token_401(client):
    body = _assert_error(client.get(URL, headers=auth(expires_in=-3600)), 401, "UNAUTHORIZED")
    assert "expired" in body["message"].lower()


def test_refresh_token_type_rejected_401(client):
    _assert_error(client.get(URL, headers=auth(typ="refresh")), 401, "UNAUTHORIZED")


@pytest.mark.parametrize("kwargs", [{"issuer": "someone-else"}, {"audience": "other-api"}])
def test_wrong_issuer_or_audience_401(client, kwargs):
    _assert_error(client.get(URL, headers=auth(**kwargs)), 401, "UNAUTHORIZED")


def test_unsigned_token_rejected(client):
    import jwt

    token = jwt.encode({"sub": "1", "roles": ["SUPER_ADMIN"], "typ": "access"}, key=None, algorithm="none")
    _assert_error(client.get(URL, headers={"Authorization": f"Bearer {token}"}), 401, "UNAUTHORIZED")


def test_garbage_token_401(client):
    _assert_error(client.get(URL, headers={"Authorization": "Bearer not.a.jwt"}), 401, "UNAUTHORIZED")


@pytest.mark.parametrize("role", ["TRAINER", "RECEPTIONIST", "MEMBER"])
def test_non_analytics_roles_403(client, role):
    _assert_error(client.get(URL, headers=auth([role], [1])), 403, "ACCESS_DENIED")


def test_manager_other_branch_403(client, recorder):
    resp = client.get(URL, params={"branchId": 2}, headers=auth(["MANAGER"], [1]))
    _assert_error(resp, 403, "ACCESS_DENIED")
    assert recorder.calls == []  # no data was read


def test_manager_scoped_to_assigned_branches(client, recorder):
    resp = client.get(URL, headers=auth(["MANAGER"], [1, 3]))
    assert resp.status_code == 200
    assert resp.json()["meta"]["scopeBranchIds"] == [1, 3]
    assert resp.json()["meta"]["branchId"] is None
    assert recorder.calls == [("attendance.fetch_visits", (1, 3))]


def test_manager_explicit_assigned_branch(client, recorder):
    resp = client.get(URL, params={"branchId": 1}, headers=auth(["MANAGER"], [1, 3]))
    assert resp.status_code == 200
    assert recorder.calls == [("attendance.fetch_visits", (1,))]
    assert resp.json()["summary"]["totalVisits"] == 2  # only branch 1 visits


def test_admin_sees_all_branches(client, recorder):
    resp = client.get(URL, headers=auth(["GYM_ADMIN"]))
    assert resp.status_code == 200
    assert resp.json()["meta"]["scopeBranchIds"] is None
    assert recorder.calls == [("attendance.fetch_visits", None)]


def test_super_admin_any_branch(client, recorder):
    resp = client.get(URL, params={"branchId": 2}, headers=auth(["SUPER_ADMIN"]))
    assert resp.status_code == 200
    assert recorder.calls == [("attendance.fetch_visits", (2,))]


def test_spring_style_role_prefix_accepted(client):
    assert client.get(URL, headers=auth(["ROLE_GYM_ADMIN"])).status_code == 200


def test_manager_with_admin_role_is_admin():
    p = Principal(user_id="1", email=None, roles=frozenset({"MANAGER", "GYM_ADMIN"}), branches=frozenset({1}))
    assert resolve_scope(p, None).branch_ids is None


def test_manager_without_branches_gets_empty_scope():
    p = Principal(user_id="1", email=None, roles=frozenset({"MANAGER"}), branches=frozenset())
    assert resolve_scope(p, None).branch_ids == ()
    with pytest.raises(AccessDeniedError):
        resolve_scope(p, 5)


def test_short_secret_fails_fast():
    with pytest.raises(ValidationError, match="at least 32 bytes"):
        Settings(jwt_secret="too-short", database_url="postgresql://x@y/z")


def test_database_url_normalised_to_psycopg():
    s = Settings(jwt_secret="x" * 32, database_url="postgresql://u:p@db:5432/gym")
    assert s.database_url == "postgresql+psycopg://u:p@db:5432/gym"


def test_token_claims_parsed():
    from app.core.config import get_settings
    from app.core.security import decode_access_token

    p = decode_access_token(mint_token(["MANAGER"], [4, "5"], sub="77"), get_settings())
    assert p.user_id == "77" and p.roles == {"MANAGER"} and p.branches == {4, 5}


def test_preflight_rejects_weak_secret(monkeypatch, capsys):
    from app import preflight
    from app.core.config import get_settings

    get_settings.cache_clear()
    monkeypatch.setenv("JWT_SECRET", "short")
    try:
        assert preflight.main() == 1
        assert "JWT_SECRET must be at least 32 bytes" in capsys.readouterr().err
    finally:
        get_settings.cache_clear()
        monkeypatch.undo()
    assert preflight.main() == 0
    get_settings.cache_clear()
