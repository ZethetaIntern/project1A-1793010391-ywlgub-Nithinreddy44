import hashlib
import json
from typing import Dict, Any, Optional

class IdempotencyService:
    def __init__(self):
        # In-memory fast cache simulating Redis TTL
        self._cache: Dict[str, Dict[str, Any]] = {}

    @staticmethod
    def compute_payload_hash(payload: Dict[str, Any]) -> str:
        """Computes deterministic SHA-256 fingerprint of request payload."""
        canonical_str = json.dumps(payload, sort_keys=True, default=str)
        return hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()

    async def get_stored_response(self, idempotency_key: str) -> Optional[Dict[str, Any]]:
        return self._cache.get(idempotency_key)

    async def store_response(self, idempotency_key: str, payload_hash: str, response_data: Dict[str, Any]):
        self._cache[idempotency_key] = {
            "payload_hash": payload_hash,
            "response": response_data
        }

idempotency_service = IdempotencyService()
