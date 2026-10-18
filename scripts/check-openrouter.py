"""Read-only CI smoke check; never writes credentials or response bodies."""
import json
import os
import sys
import urllib.error
import urllib.request

MODEL = "openai/whisper-1"
key = os.environ.get("OPENROUTER_API_KEY", "")
if not key:
    sys.exit("OPENROUTER_API_KEY is required")


def get(path):
    request = urllib.request.Request(
        "https://openrouter.ai/api/v1/" + path,
        headers={"Authorization": "Bearer " + key, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"OpenRouter GET failed (HTTP {error.code})")
    except (urllib.error.URLError, TimeoutError, ValueError):
        sys.exit("OpenRouter GET failed or returned invalid JSON")


get("key")  # The public transcription catalog alone does not verify authentication.
models = get("models?output_modalities=transcription")
if not any(model.get("id") == MODEL for model in models.get("data", [])):
    sys.exit(f"Expected model is absent from the transcription catalog: {MODEL}")
print(f"OpenRouter authentication and model discovery passed: {MODEL}")
