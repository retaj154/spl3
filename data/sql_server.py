#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3

IMPORTANT:
DO NOT CHANGE the server name or the basic protocol.
Students should EXTEND this server by implementing
the methods below.

Protocol (DO NOT CHANGE):
- Client sends an SQL string terminated with a null byte (\0)
- Server responds with a string terminated with a null byte (\0)

Response format:
- SUCCESS                     (for commands that succeed)
- SUCCESS|row1|row2|...        (for SELECT queries; each row is comma-separated)
- ERROR|<message>              (on any error)

Notes:
- This server uses SQLite file DB_FILE.
- You may change the schema as you wish (relational design principles).
"""

import socket
import sys
import threading
import sqlite3

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")


def init_database():
    """Initialize DB schema (idempotent). Also performs minimal migrations."""
    conn = sqlite3.connect(DB_FILE)
    cur = conn.cursor()

    # Users (must store password for STOMP login semantics)
    cur.execute(
        "CREATE TABLE IF NOT EXISTS users ("
        "username TEXT PRIMARY KEY,"
        "password TEXT NOT NULL"
        ")"
    )

    # Sessions: login/logout timestamps
    cur.execute(
        "CREATE TABLE IF NOT EXISTS sessions ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "username TEXT NOT NULL,"
        "login_time TEXT NOT NULL,"
        "logout_time TEXT,"
        "FOREIGN KEY(username) REFERENCES users(username)"
        ")"
    )

    # File uploads tracked per report command
    cur.execute(
        "CREATE TABLE IF NOT EXISTS file_logs ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "username TEXT NOT NULL,"
        "filename TEXT NOT NULL,"
        "game_channel TEXT,"
        "upload_time TEXT NOT NULL,"
        "FOREIGN KEY(username) REFERENCES users(username)"
        ")"
    )

    # --- Minimal migration support (if a previous table existed without password) ---
    try:
        cur.execute("PRAGMA table_info(users)")
        cols = {row[1] for row in cur.fetchall()}
        if "password" not in cols:
            cur.execute("ALTER TABLE users ADD COLUMN password TEXT")
            cur.execute("UPDATE users SET password='' WHERE password IS NULL")
            conn.commit()
    except Exception:
        # If migration fails, we still keep going; this is best-effort.
        pass

    conn.commit()
    conn.close()
    print(f"[{SERVER_NAME}] Database initialized at '{DB_FILE}'.")


def _success(rows=None) -> str:
    if rows is None:
        return "SUCCESS"
    if not rows:
        return "SUCCESS"
    parts = ["SUCCESS"]
    for r in rows:
        parts.append(",".join("" if v is None else str(v) for v in r))
    return "|".join(parts)


def _error(msg: str) -> str:
    return f"ERROR|{msg}"


def execute_sql(sql: str) -> str:
    try:
        conn = sqlite3.connect(DB_FILE)
        cur = conn.cursor()

        sql_stripped = sql.strip()
        is_select = sql_stripped.upper().startswith("SELECT")

        cur.execute(sql)

        if is_select:
            rows = cur.fetchall()
            conn.close()
            return _success(rows)

        conn.commit()
        conn.close()
        return _success()

    except Exception as e:
        try:
            conn.close()
        except Exception:
            pass
        return _error(str(e))


def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")
    try:
        while True:
            message = recv_null_terminated(client_socket)
            if not message:
                break

            print(f"[{SERVER_NAME}] Received SQL: {message}")
            response = execute_sql(message)
            client_socket.sendall((response + "\0").encode("utf-8"))

    except Exception as e:
        print(f"[{SERVER_NAME}] Error: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass


def start_server(host="127.0.0.1", port=7778):
    init_database()

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    start_server(port=port)
