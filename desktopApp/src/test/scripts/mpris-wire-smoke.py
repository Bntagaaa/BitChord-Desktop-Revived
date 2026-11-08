#!/usr/bin/env python3
"""Real MPRIS wire regression against the packaged Linux application.

Run inside an isolated bus: dbus-run-session -- python3 desktopApp/src/test/scripts/mpris-wire-smoke.py
The fixture restores a paused synthetic queue and consumes Next/Resume intents, so it opens neither
an audio device nor any media URL. All D-Bus calls target the fixture's process-specific service.
"""
import os
import subprocess
import pathlib
import time
import select
import json
import shutil

repo = pathlib.Path(__file__).resolve().parents[4]
image = repo / 'desktopApp/build/compose/binaries/main/app/BitChordReworked'
lib = image / 'lib/app'
output = repo / 'desktopApp/build/mpris-wire-tests'
output.mkdir(parents=True, exist_ok=True)
if not os.environ.get('DBUS_SESSION_BUS_ADDRESS'):
    raise SystemExit('Run this fixture under dbus-run-session; no session bus was supplied.')
if not lib.is_dir():
    raise SystemExit('Build the Linux application image before running the MPRIS wire fixture.')
java_home = os.environ.get('JAVA_HOME')
java = pathlib.Path(java_home) / 'bin/java' if java_home else pathlib.Path(shutil.which('java') or 'java')
javac = pathlib.Path(java_home) / 'bin/javac' if java_home else pathlib.Path(shutil.which('javac') or 'javac')
classes = output / 'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(javac), '-cp', str(lib / '*'), '-d', str(classes),
                str(pathlib.Path(__file__).with_name('MprisWireProbe.java'))], check=True)
cp = str(classes) + os.pathsep + str(lib / '*')
env = dict(os.environ)
log = (output / 'stderr.log').open('w')
probe = None
records = []
def line(process, timeout=12):
    if not select.select([process.stdout], [], [], timeout)[0]: raise RuntimeError('Fixture response timed out')
    return process.stdout.readline().strip()
try:
    probe = subprocess.Popen([str(java), '-Djava.util.prefs.userRoot=' + str(output / 'preferences'), '-Dbitchord.preferencesNode=com.music.bitchord.reworked.private-wire-test', '-cp', cp, 'MprisWireProbe'], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, bufsize=1)
    first = line(probe)
    if not first.startswith('READY '): raise RuntimeError('Fixture failed to start: '+first)
    name = first.split(' ',1)[1]
    def call(method,*args,destination=name,path='/org/mpris/MediaPlayer2',ok=True):
        result = subprocess.run(['dbus-send','--session','--print-reply','--reply-timeout=5000','--dest='+destination,path,method,*args],env=env,text=True,capture_output=True,timeout=7)
        if ok and result.returncode: raise RuntimeError(method+': '+result.stderr)
        if not ok and result.returncode == 0: raise AssertionError(method+' should have been rejected')
        records.append({'method':method,'arguments':args,'returncode':result.returncode,'reply':result.stdout.strip(),'error':result.stderr.strip()})
        return result.stdout+result.stderr
    player='org.mpris.MediaPlayer2.Player'
    props='org.freedesktop.DBus.Properties'
    def get(prop): return call(props+'.Get','string:'+player,'string:'+prop)
    def setprop(prop,value): return call(props+'.Set','string:'+player,'string:'+prop,value)
    allprops=call(props+'.GetAll','string:'+player)
    assert 'Private bus fixture' in allprops and 'int64 12000000' in allprops and 'Paused' in allprops
    assert '/com/music/bitchord/reworked/track/wire_a' in allprops
    root=call(props+'.GetAll','string:org.mpris.MediaPlayer2')
    assert 'BitChord Desktop Reworked' in root
    introspection=call('org.freedesktop.DBus.Introspectable.Introspect')
    assert player in introspection and 'Seeked' in introspection
    setprop('Volume','variant:double:0.35')
    assert '0.35' in get('Volume')
    setprop('Rate','variant:double:1.25')
    assert '1.25' in get('Rate')
    setprop('Rate','variant:double:0')
    assert '1.25' in get('Rate') and 'Paused' in get('PlaybackStatus')
    setprop('Shuffle','variant:boolean:true')
    assert 'boolean true' in get('Shuffle')
    setprop('LoopStatus','variant:string:Playlist')
    assert 'Playlist' in get('LoopStatus')
    setprop('LoopStatus','variant:string:None')
    call(player+'.Seek','int64:2000000')
    assert 'int64 14000000' in get('Position')
    call(player+'.SetPosition','objpath:/com/music/bitchord/reworked/track/stale','int64:1000000')
    assert 'int64 14000000' in get('Position')
    call(player+'.SetPosition','objpath:/com/music/bitchord/reworked/track/wire_a','int64:300000000')
    assert 'int64 14000000' in get('Position')
    call(player+'.SetPosition','objpath:/com/music/bitchord/reworked/track/wire_a','int64:7000000')
    assert 'int64 7000000' in get('Position')
    call(player+'.Seek','int64:300000000')
    probe.stdin.write('COMMANDS\n'); probe.stdin.flush()
    assert 'Next' in line(probe)
    call(player+'.Stop')
    assert 'Stopped' in get('PlaybackStatus') and 'int64 0' in get('Position')
    call(props+'.Set','string:'+player,'string:Position','variant:int64:1',ok=False)
    call(props+'.Get','string:'+player,'string:MissingProperty',ok=False)
    probe.stdin.write('CLOSE\n'); probe.stdin.flush()
    assert line(probe)=='CLOSED'
    deadline=time.monotonic()+3
    while True:
        owns=call('org.freedesktop.DBus.NameHasOwner','string:'+name,destination='org.freedesktop.DBus',path='/org/freedesktop/DBus')
        if 'boolean false' in owns: break
        if time.monotonic()>deadline: raise AssertionError('MPRIS name survived adapter close')
        time.sleep(.05)
    print(json.dumps({'passed':True,'wire_calls':len(records),'network':False,'audio_device':False}))
finally:
    (output/'results.json').write_text(json.dumps(records,indent=2))
    if probe is not None:
        try: probe.stdin.write('EXIT\n'); probe.stdin.flush(); probe.wait(timeout=3)
        except Exception: probe.kill(); probe.wait()
    log.close()
