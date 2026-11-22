# Project Plan: Client Application Starter

## Folder Structure
```
client_app_starter/
├── .env.example
├── config/
│   ├── __init__.py
│   ├── settings.py
│   └── keys.json
├── auth/
│   ├── __init__.py
│   ├── verifier.py
│   └── models.py
├── core/
│   ├── __init__.py
│   ├── app.py
│   └── hooks.py
├── ui/
│   ├── __init__.py
│   ├── cli.py
│   └── display.py
├── utils/
│   ├── __init__.py
│   └── logger.py
├── main.py
├── requirements.txt
└── README.md
```

## Planned Modules and Operations

1. `utils/logger.py`:
   - Structured logging setup with console and file handlers.
   - Trace masking for sensitive parameters.

2. `config/settings.py` & `config/keys.json`:
   - Environment loader using `.env` fallback.
   - Structured client key registry with metadata (client_id, key_hash, status: active/expired/disabled, expiration_date).
   - Validation configuration parameters.

3. `auth/verifier.py` & `auth/models.py`:
   - SHA-256 / PBKDF2 hash-based comparison to prevent raw key exposure.
   - Verification logic: checks presence, status (active/expired/disabled), and validity.
   - Standardized auth result objects: success state, sanitized client-facing error message, internal debug log.

4. `core/app.py` & `core/hooks.py`:
   - `run_app()` protected execution entry point.
   - Pluggable lifecycle hooks (`before_start`, `main_task`, `on_shutdown`).

5. `ui/cli.py` & `ui/display.py`:
   - Terminal interface prompting for client key or reading from environment flag.
   - Clean status messages and UI state transitions.

6. `main.py`:
   - Root application bootstrap orchestrating configuration, authentication gate, and main application execution.

7. `README.md` & `requirements.txt`:
   - Distribution, packaging (e.g. PyInstaller), key provisioning, and execution instructions.
