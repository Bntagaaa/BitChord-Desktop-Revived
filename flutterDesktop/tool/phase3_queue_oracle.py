"""Execute the pinned Kotlin queue source against deterministic action traces.
No protected source is changed; only temporary files with a minimal Song DTO.
Needs a Kotlin compiler executable, or the existing Gradle compiler cache.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
REF = '348b613a0c3db1a54de56483ea6f8748d6465211'
PATHS = ['desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQueue.kt',
         'shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueTimeline.kt']
STUB = '''package com.music.bitchord.data.model

enum class QueueTier { USER_QUEUE, CONTEXT, AUTOPLAY }
enum class PlaybackSourceType { QUEUE }
data class Song(val videoId:String, val title:String="", val artist:String="", val thumbnailUrl:String?=null,
 val fromAutoplay:Boolean=false, val queueEntryId:String?=null,
 val queueTier:QueueTier=if(fromAutoplay) QueueTier.AUTOPLAY else QueueTier.CONTEXT,
 val isVideo:Boolean=false, val isVideoOrigin:Boolean=false, val radioName:String?=null,
 val playbackSource:String?=null, val playbackSourceType:PlaybackSourceType?=null,
 val playbackSourceId:String?=null, val durationText:String?=null,
 val artistId:String?=null,val albumId:String?=null,val albumName:String?=null,
 val localUri:String?=null,val localPath:String?=null,val setVideoId:String?=null,
 val localDateAddedSeconds:Long?=null,val localDateModifiedSeconds:Long?=null,
 val sourceQuality:String?=null,val isExplicit:Boolean?=null)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlinc')
    parser.add_argument('--gradle-cache', default=str(Path.home() / '.gradle/caches/modules-2/files-2.1'))
    args = parser.parse_args()
    rng = random.Random(731)
    cases, code = [], []
    tiers = ['context', 'user', 'context', 'autoplay', 'user', 'context', 'autoplay']
    enum = {'context': 'CONTEXT', 'user': 'USER_QUEUE', 'autoplay': 'AUTOPLAY'}
    for case in range(25):
        rows = [{'id': f'{case}:{i}', 'catalog': str(i % 3), 'tier': t} for i, t in enumerate(tiers)]
        cursor = rng.randrange(len(rows))
        actions = []
        code.append('run { var q=DesktopQueue(listOf(' + ','.join(
            f'Song("{e["catalog"]}",queueEntryId="{e["id"]}",queueTier=QueueTier.{enum[e["tier"]]})' for e in rows) + f'),{cursor})')
        for step in range(18):
            op = rng.choice(['next', 'previous', 'jump', 'remove', 'move', 'clear', 'enqueue'])
            a, b = rng.randrange(9), rng.randrange(9)
            new_id = f'{case}:new{step}'
            if op in ['next', 'previous']:
                action = [op]; expression = f'q.{op}()'
            elif op == 'jump':
                action = [op, a]; expression = f'q.jumpTo({a})'
            elif op == 'remove':
                action = [op, a]; expression = f'q.removeAt({a})'
            elif op == 'move':
                action = [op, a, b]; expression = f'q.move({a},{b})'
            elif op == 'clear':
                action = [op]; expression = 'q.withoutUserQueue()'
            else:
                action = [op, new_id, bool(a % 2)]
                expression = f'q.enqueue(Song("dup",queueEntryId="{new_id}"),{str(bool(a % 2)).lower()})'
            actions.append(action)
            code.append(f'q={expression}; emit({case},{step},q)')
        code.append('}')
        cases.append({'rows': rows, 'cursor': cursor, 'actions': actions, 'expected': []})
    persistence_path = 'desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPersistence.kt'
    persistence = subprocess.check_output(['git', 'show', f'{REF}:{persistence_path}'], cwd=ROOT).decode()
    encoder = persistence.split('    private fun encodeSong', 1)[1].split('    private fun decodeSong', 1)[0]
    encoder = 'private fun encodeSong' + encoder
    imports = 'import java.util.Base64\nimport java.nio.charset.StandardCharsets\n'
    encode_helper = 'private const val DELIMITER="|"\nprivate fun encode(value:String):String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))\n'
    migration_calls = 'println("MIGRATION:"+encodeSong(Song("local:fixture", "A song | 🎵", "Artist", localUri="file:///missing/song.flac", localPath="/missing/song.flac", sourceQuality="FLAC", isExplicit=true)))\n'
    harness = '''package com.music.bitchord.desktop
import com.music.bitchord.data.model.*
''' + imports + encoder + encode_helper + '''
internal fun emit(case:Int,step:Int,q:DesktopQueue) {
 println("$case,$step,${q.index}:"+q.songs.joinToString(",") { "${it.queueEntryId}/${it.queueTier.name}" })
}
fun main() {
''' + migration_calls + '\n'.join(code) + '\n}\n'
    with tempfile.TemporaryDirectory(prefix='bitchord-queue-oracle-') as directory:
        temp = Path(directory)
        sources, hashes = [], {}
        for path in PATHS:
            data = subprocess.check_output(['git', 'show', f'{REF}:{path}'], cwd=ROOT)
            destination = temp / Path(path).name
            destination.write_bytes(data)
            sources.append(str(destination))
            hashes[path] = hashlib.sha256(data).hexdigest()
        (temp / 'Models.kt').write_text(STUB)
        (temp / 'Oracle.kt').write_text(harness)
        sources += [str(temp / 'Models.kt'), str(temp / 'Oracle.kt')]
        if args.kotlinc:
            subprocess.run([args.kotlinc, *sources, '-include-runtime', '-d', str(temp / 'oracle.jar')], check=True)
            run = ['java', '-jar', str(temp / 'oracle.jar')]
        else:
            cache = Path(args.gradle_cache)
            versions = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.20',
                        'org.jetbrains.kotlin/kotlin-stdlib/2.3.20',
                        'org.jetbrains.kotlin/kotlin-script-runtime/2.3.20',
                        'org.jetbrains.kotlin/kotlin-reflect/2.0.20',
                        'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2',
                        'org.jetbrains/annotations/23.0.0']
            jars = [next((cache / version).rglob('*.jar')) for version in versions]
            import os
            cp = os.pathsep.join(map(str, jars))
            subprocess.run(['java', '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                            '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(temp / 'classes'), *sources], check=True)
            run = ['java', '-cp', str(temp / 'classes') + os.pathsep + cp, 'com.music.bitchord.desktop.OracleKt']
        output = subprocess.check_output(run, text=True)
    migration_lines = []
    for line in output.splitlines():
        if line.startswith('MIGRATION:'):
            migration_lines.append(line[len('MIGRATION:'):])
            continue
        prefix, rows = line.split(':', 1)
        case, step, cursor = map(int, prefix.split(','))
        entries = [row.split('/') for row in rows.split(',')] if rows else []
        cases[case]['expected'].append({'cursor': cursor, 'ids': [e[0] for e in entries],
                                      'tiers': [e[1] for e in entries]})
    assert sum(len(c['expected']) for c in cases) == 450
    assert len(migration_lines) == 1
    migration = {'reference': REF, 'encoder_path': persistence_path, 'encoder_sha256': hashlib.sha256(persistence.encode()).hexdigest(), 'songs': migration_lines}
    (ROOT / 'flutterDesktop/test/fixtures/phase3/migration-oracle.json').write_text(json.dumps(migration, indent=2) + '\n')
    target = ROOT / 'flutterDesktop/test/fixtures/phase3/queue-oracle.json'
    target.write_text(json.dumps({'reference': REF, 'hashes': hashes, 'cases': cases}, indent=2) + '\n')
    print(f'Executed {len(cases)} traces / {sum(len(c["actions"]) for c in cases)} Kotlin queue transitions')


if __name__ == '__main__':
    main()
