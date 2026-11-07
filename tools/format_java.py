"""Format Java with google-java-format 1.37.0, AOSP spacing, and unchanged string literals."""
from pathlib import Path
import argparse
import os
import subprocess

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--formatter', required=True, type=Path, help='Path to google-java-format-1.37.0-all-deps.jar')
parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
java = str(args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if args.java_home else 'java'
files = sorted({p for folder in ('src', 'tools/checks') for p in (root / folder).rglob('*.java')})
options = ['--dry-run', '--set-exit-if-changed'] if args.check else ['--replace']
subprocess.run([java, '-jar', str(args.formatter), '--aosp', '--skip-reflowing-long-strings', *options, *map(str, files)], check=True)
