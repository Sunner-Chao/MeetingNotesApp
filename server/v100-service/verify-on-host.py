"""Run synthetic protocol validation locally on the GPU host."""
import argparse
import json
import importlib.util
import sys
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--environment", required=True)
parser.add_argument("--audio", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--browser", action="store_true")
args = parser.parse_args()
config = json.loads(Path(args.environment).read_text(encoding="utf-8-sig"))
spec = importlib.util.spec_from_file_location("verify_v100", Path(__file__).with_name("verify-v100.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
import time
import asyncio
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "common"))
from account_stt_token import issue_account_stt_token
token = issue_account_stt_token(config["ACCOUNT_TOKEN_SECRET"], "v100-validation", int(time.time()) + 3600)
params = argparse.Namespace(url="http://127.0.0.1:8889", audio=Path(args.audio), output=Path(args.output),
                            pause=True, browser=args.browser, file=True)
asyncio.run(module.verify(params, token))
