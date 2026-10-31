#!/usr/bin/env python3
"""Build real adapter faults in private copies; compile failures never count as catches."""
from pathlib import Path
import os, shutil, subprocess, sys, tempfile

repo = Path(__file__).resolve().parent.parent
source_rel = Path('src/main/java/io/lmctl/sample/Main.java')
with tempfile.TemporaryDirectory(prefix='lmcode-java-mutants-') as tmp:
    work = Path(tmp) / 'sample'
    shutil.copytree(repo, work, ignore=shutil.ignore_patterns('.git', 'target', 'build', '__pycache__'))
    source = work / source_rel
    original = source.read_text()
    host = work / 'bin/lmcode-java-sample'
    bash = 'bash'
    if os.name == 'nt':
        bash = str(Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Git' / 'bin' / 'bash.exe')
    def build():
        p = subprocess.run([bash, 'scripts/build-jvm.sh'], cwd=work, capture_output=True)
        if p.returncode: raise AssertionError(('mutant compile failed', p.stdout, p.stderr))
    def check():
        p = subprocess.run([sys.executable, str(work/'scripts/check-invocations.py'), str(host)], cwd=work, capture_output=True)
        if p.returncode == 0: raise AssertionError(('mutant survived', p.stdout, p.stderr))
    build()
    p = subprocess.run([sys.executable, str(work/'scripts/check-invocations.py'), str(host)], cwd=work, capture_output=True)
    assert p.returncode == 0, (p.stdout, p.stderr)
    mutants = [
        ('stale bare frame', 'return results.writeFrame(System.out);', 'System.out.println("{\\"ok\\":true,\\"result\\":null}"); return results.writeFrame(System.out);'),
        ('missing part', 'files.add(args[i + 1]); priorities.add', '/* dropped argv part */ priorities.add'),
        ('duplicate part', 'files.add(args[i + 1]); priorities.add', 'files.add(args[i + 1]); files.add(args[i + 1]); priorities.add'),
        ('argv role loss', 'priorities.add(args[i].equals("--first") ? 0 : args[i].equals("--last") ? 2 : 1);', 'priorities.add(1);'),
        ('reset shared host counter', 'InvocationResults.Registration part = results.registrations().get(position);', 'InvocationResults.Registration part = results.registrations().get(position); host.ledger.balance = 0; host.ledger.entries = 0;'),
    ]
    for name, old, new in mutants:
        assert original.count(old) == 1, (name, 'anchor')
        source.write_text(original.replace(old, new))
        build(); check()
        print('CAUGHT ' + name, flush=True)
        source.write_text(original)
    build()
print('PASS Java invocation mutation controls')
