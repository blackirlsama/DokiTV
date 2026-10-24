#!/usr/bin/env python3
"""Linux deployment controller. No shell eval, secret printing, volume deletion, or automatic DB reset."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / 'deploy'
ALLOWED = {'TIANMU_STACK_NAME', 'TIANMU_SECRETS_DIR', 'TIANMU_IMAGE_TAG', 'AGENT_HTTP_PORT',
           'AGENT_MEMORY_LIMIT', 'MYSQL_IMAGE', 'REDIS_IMAGE', 'MAVEN_IMAGE', 'JAVA_IMAGE',
           'BUSINESS_BASE_URL', 'DEEPSEEK_BASE_URL', 'PREPARATION_MODEL', 'HARNESS_MODEL', 'DEPLOYMENT_MODE',
           'TRACE_MODE', 'KAFKA_IMAGE', 'KAFKA_CLUSTER_ID', 'KAFKA_TRACE_TOPIC', 'CLICKHOUSE_IMAGE'}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def run(command, capture=False):
    environment = {key: value for key, value in os.environ.items() if key not in ALLOWED}
    if capture:
        return subprocess.check_output(command, cwd=str(ROOT), env=environment).decode('utf-8').strip()
    subprocess.check_call(command, cwd=str(ROOT), env=environment)


def read_env(path):
    result = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, separator, value = line.partition('=')
        require(separator and key in ALLOWED, 'Unsupported runtime setting; put credentials in secret files')
        require('\x00' not in value and '\r' not in value, 'Invalid runtime value')
        result[key] = value
    require(re.fullmatch(r'[a-z0-9][a-z0-9_-]{1,40}', result.get('TIANMU_STACK_NAME', '')), 'Invalid stack name')
    require(result.get('AGENT_HTTP_PORT', '').isdigit() and 1024 <= int(result['AGENT_HTTP_PORT']) <= 65535, 'Invalid loopback port')
    require(result.get('TRACE_MODE', 'file') in ('file', 'kafka'), 'TRACE_MODE must be file or kafka')
    if result.get('TRACE_MODE') == 'kafka':
        require(re.fullmatch(r'[A-Za-z0-9_-]{22}', result.get('KAFKA_CLUSTER_ID', '')), 'Set a stable 22-character Kafka cluster UUID')
        topic = result.get('KAFKA_TRACE_TOPIC', 'Tianmu.agent.trace.v1')
        require(re.fullmatch(r'[A-Za-z0-9._-]{1,249}', topic) and topic not in ('.', '..'), 'Invalid Kafka trace topic')
    return result


def write_env(path, values):
    with os.fdopen(os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
        for key, value in sorted(values.items()):
            require(key in ALLOWED and '\n' not in value and not any(c in value for c in '$\r\x00'), 'Invalid release setting')
            file.write(key + '=' + value + '\n')


def initialize(directory):
    require(os.name == 'posix' and os.geteuid() == 0, 'Initialize private configuration as root on Linux')
    base = Path(directory).resolve()
    require(base != ROOT and ROOT not in base.parents, 'Configuration belongs outside the source checkout')
    require(not (base / 'runtime.env').exists(), 'Configuration already exists; credentials will not be rotated')
    secret_dir = base / 'secrets'
    secret_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(str(base), 0o700)
    os.chmod(str(secret_dir), 0o700)
    for name in ('agent-db-password', 'mysql-root-password', 'redis-password', 'clickhouse-password'):
        path = secret_dir / name
        require(not path.exists(), 'Partial configuration exists; review it before retrying initialization')
        with os.fdopen(os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400), 'w') as file:
            file.write(secrets.token_hex(32))
        # Compose file-backed secrets preserve host ownership. Only the application UID can read them.
        os.chown(str(path), 10001, 10001)
    values = read_env(DEPLOY / '.env.example')
    values['TIANMU_SECRETS_DIR'] = str(secret_dir)
    values['KAFKA_CLUSTER_ID'] = base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip('=')
    write_env(base / 'runtime.env', values)
    print(json.dumps({'initialized': str(base / 'runtime.env'), 'secretsPrinted': False}))


def preflight(values, business=False):
    require(sys.platform.startswith('linux'), 'Deployment requires a Linux Docker host')
    info = json.loads(run(['docker', 'info', '--format', '{{json .}}'], True))
    require(info.get('OSType') == 'linux', 'Linux containers are required')
    require(info.get('MemTotal', 0) >= 1800 * 1024 * 1024, 'At least 1.8 GiB host memory is required')
    if values.get('TRACE_MODE') == 'kafka':
        require(info.get('MemTotal', 0) >= 3000 * 1024 * 1024, 'Kafka staging deployment requires at least 3 GiB host memory')
    require(shutil.disk_usage(str(ROOT)).free >= 5 * 1024 ** 3, 'Free at least 5 GiB before deployment')
    version = run(['docker', 'compose', 'version', '--short'], True)
    match = re.search(r'(\d+)\.(\d+)', version)
    require(match and tuple(map(int, match.groups())) >= (2, 20), 'Docker Compose >= 2.20 is required')
    os_release = Path('/etc/os-release').read_text()
    if 'ID="centos"' in os_release and 'VERSION_ID="7"' in os_release:
        require(not business, 'CentOS 7 is unsupported: use it for staging only, then migrate to a maintained OS')
        print('NOTICE: legacy CentOS 7 host; staging validation only', file=sys.stderr)
    if business:
        url = urlsplit(values.get('BUSINESS_BASE_URL', ''))
        require(url.scheme == 'https' and url.hostname and not url.username and not url.password and not url.query and not url.fragment,
                'Business mode requires an HTTPS bridge URL without credentials or a query')
        require((Path(values['TIANMU_SECRETS_DIR']) / 'model-api-key').stat().st_size > 0, 'Provide a private model key file')


def enable_kafka(env_path):
    require(os.name == 'posix' and os.geteuid() == 0, 'Configure Kafka as root on Linux')
    require(Path(env_path).name == 'runtime.env', 'Change runtime.env, never a built release.env')
    values = read_env(env_path)
    secret = Path(values['TIANMU_SECRETS_DIR']) / 'clickhouse-password'
    if not secret.exists():
        with os.fdopen(os.open(str(secret), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400), 'w') as file:
            file.write(secrets.token_hex(32))
        os.chown(str(secret), 10001, 10001)
    values['TRACE_MODE'] = 'kafka'
    values.setdefault('KAFKA_CLUSTER_ID', base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip('='))
    values.setdefault('KAFKA_IMAGE', 'apache/kafka:3.9.1')
    values.setdefault('CLICKHOUSE_IMAGE', 'clickhouse/clickhouse-server:25.8')
    values.setdefault('KAFKA_TRACE_TOPIC', 'Tianmu.agent.trace.v1')
    temporary = Path(env_path).with_name('runtime-' + secrets.token_hex(8) + '.tmp')
    write_env(temporary, values)
    os.replace(str(temporary), str(env_path))
    print(json.dumps({'traceMode': 'kafka', 'destination': 'clickhouse', 'secretsPrinted': False}))


def source_hash():
    files = [ROOT / 'tianmu-agent' / 'pom.xml', ROOT / '.dockerignore']
    files += list((ROOT / 'tianmu-agent' / 'src').rglob('*'))
    files += [DEPLOY / n for n in ('Dockerfile', 'entrypoint.sh', 'HealthProbe.java')]
    digest = hashlib.sha256()
    for path in sorted(p for p in files if p.is_file()):
        require(not path.is_symlink(), 'Symlink build input is not allowed')
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix in ('.pem', '.key') or path.name.startswith(('.env', 'application-local', 'application-secrets')) or path.name.endswith('-env.json') or 'fixture' in path.name and path.suffix == '.json':
            continue
        digest.update(relative.encode()); digest.update(b'\0'); digest.update(path.read_bytes()); digest.update(b'\0')
    return digest.hexdigest()


def pinned_image(reference):
    run(['docker', 'pull', reference])
    digests = json.loads(run(['docker', 'image', 'inspect', '--format', '{{json .RepoDigests}}', reference], True))
    require(digests, 'An upstream image digest is required')
    return digests[0]


def compose(release, values):
    folder = Path(release).parent if (Path(release).parent / 'manifest.json').is_file() else DEPLOY
    command = ['docker', 'compose', '--env-file', str(release), '-p', values['TIANMU_STACK_NAME'], '-f', str(folder / 'compose.yaml')]
    if values.get('DEPLOYMENT_MODE') == 'business':
        command += ['-f', str(folder / 'compose.business.yaml')]
    if values.get('TRACE_MODE') == 'kafka':
        command += ['-f', str(folder / 'compose.kafka.yaml')]
    return command


def build(env_path, business):
    values = read_env(env_path)
    preflight(values, business)
    digest = source_hash()
    release_id = datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ') + '-' + digest[:12]
    folder = Path(env_path).resolve().parent / 'releases' / release_id
    folder.mkdir(parents=True, mode=0o700)
    defaults = {'MAVEN_IMAGE': 'maven:3.10.0-eclipse-temurin-17', 'JAVA_IMAGE': 'eclipse-temurin:17-jre-jammy',
                'MYSQL_IMAGE': 'mysql:8.4', 'REDIS_IMAGE': 'redis:7.4'}
    if values.get('TRACE_MODE') == 'kafka':
        defaults['KAFKA_IMAGE'] = 'apache/kafka:3.9.1'
        defaults['CLICKHOUSE_IMAGE'] = 'clickhouse/clickhouse-server:25.8'
    for key, default in defaults.items():
        values[key] = pinned_image(values.get(key, default))
    values['TIANMU_IMAGE_TAG'] = release_id
    values['DEPLOYMENT_MODE'] = 'business' if business else 'staging'
    image = 'tianmu-agent:' + release_id
    run(['docker', 'build', '--build-arg', 'MAVEN_IMAGE=' + values['MAVEN_IMAGE'], '--build-arg', 'JAVA_IMAGE=' + values['JAVA_IMAGE'],
         '--build-arg', 'SOURCE_REVISION=' + digest, '-f', str(DEPLOY / 'Dockerfile'), '-t', image, str(ROOT)])
    write_env(folder / 'release.env', values)
    image_id = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], True)
    deployment_files = ('compose.yaml', 'compose.business.yaml', 'compose.kafka.yaml', 'redis-entrypoint.sh',
                        'clickhouse-config.xml', 'clickhouse-profiles.xml', 'clickhouse-init.sh', 'clickhouse-trace.sql')
    for name in deployment_files:
        shutil.copyfile(str(DEPLOY / name), str(folder / name))
    manifest = {'schemaVersion': 1, 'releaseId': release_id, 'sourceSha256': digest, 'image': image, 'imageId': image_id,
                'mode': values['DEPLOYMENT_MODE'], 'traceMode': values.get('TRACE_MODE', 'file'),
                'traceSettings': {k: values.get(k) for k in ('KAFKA_CLUSTER_ID', 'KAFKA_TRACE_TOPIC')},
                'upstreamImages': {k: values[k] for k in defaults},
                'deploymentFiles': {name: hashlib.sha256((folder / name).read_bytes()).hexdigest() for name in deployment_files}}
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'release': str(folder / 'release.env'), 'imageId': image_id}))


def activate(release):
    values = read_env(release)
    preflight(values, values.get('DEPLOYMENT_MODE') == 'business')
    manifest = json.loads((Path(release).parent / 'manifest.json').read_text())
    image = 'tianmu-agent:' + values['TIANMU_IMAGE_TAG']
    require(manifest['image'] == image and manifest['mode'] == values.get('DEPLOYMENT_MODE'), 'Release manifest mismatch')
    require(manifest.get('traceMode', 'file') == values.get('TRACE_MODE', 'file'), 'Release trace mode was changed')
    if manifest.get('traceMode') == 'kafka':
        require(all(values.get(k) == v for k, v in manifest['traceSettings'].items()), 'Release Kafka settings were changed')
    require(run(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], True) == manifest['imageId'], 'Release image was replaced')
    require(all(values.get(k) == v for k, v in manifest['upstreamImages'].items()), 'Pinned image settings were changed')
    require(manifest.get('schemaVersion') == 1, 'Unsupported release manifest')
    require(all(hashlib.sha256((Path(release).parent / name).read_bytes()).hexdigest() == digest
                for name, digest in manifest['deploymentFiles'].items()), 'Release Compose configuration was modified')
    command = compose(release, values)
    run(command + ['config', '--quiet'])
    # One writer per database: stop the old Agent before starting its replacement. Volumes are retained.
    run(command + ['stop', 'agent'])
    run(command + ['up', '-d', '--wait', '--wait-timeout', '180'])
    verify(values)
    current = Path(release).resolve().parents[2] / 'current-release'
    current.write_text(str(Path(release).resolve()) + '\n')


def request(base, method, path, body=None, authenticated=True):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if authenticated:
        headers['Authorization'] = 'Bearer demo-alice'
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as response:
        raw = response.read()
        return raw.decode('utf-8') if 'text/event-stream' in response.headers.get('Content-Type', '') else json.loads(raw)


def verify(values):
    base = 'http://127.0.0.1:' + values['AGENT_HTTP_PORT']
    for probe in ('liveness', 'readiness'):
        require(request(base, 'GET', '/actuator/health/' + probe, authenticated=False)['status'] == 'UP', 'Health probe failed')
    if values.get('DEPLOYMENT_MODE') == 'business':
        print(json.dumps({'status': 'PASS', 'mode': 'business', 'checks': ['liveness', 'readiness'], 'businessQueryVerified': False}))
        return
    try:
        request(base, 'POST', '/api/agent/runs', {'message': '你能做什么'}, authenticated=False)
        raise RuntimeError('Anonymous Agent request was accepted')
    except urllib.error.HTTPError as error:
        require(error.code == 401, 'Unexpected anonymous request response')
    metric_trace = None
    for question, route in [('你能做什么', 'FAQ'), ('查询视频1001昨天的弹幕数', 'HARNESS')]:
        created = request(base, 'POST', '/api/agent/runs', {'message': question})
        path = '/api/agent/runs/' + created['runId']
        end = time.monotonic() + 30
        while True:
            result = request(base, 'GET', path)
            if result['status'] not in ('QUEUED', 'RUNNING'):
                break
            require(time.monotonic() < end, 'Run did not terminate')
            time.sleep(0.1)
        require(result['status'] == 'SUCCEEDED' and result['route'] == route, 'Unexpected route or result')
        if route == 'HARNESS':
            require('128' in result['answer']['text'] and result['answer']['evidenceIds'], 'Demo evidence missing')
            metric_trace = result['traceId']
        require(request(base, 'GET', path + '/events').count('event:DONE') == 1, 'SSE terminal delivery failed')
    checks = ['liveness', 'readiness', 'authentication', 'faq', 'metric-evidence', 'sse']
    archived = None
    if values.get('TRACE_MODE') == 'kafka':
        archived = verify_kafka_clickhouse(values, metric_trace)
        checks.append('kafka-clickhouse')
    print(json.dumps({'status': 'PASS', 'mode': 'staging', 'checks': checks, 'paidModelCalls': 0,
                      'traceSink': values.get('TRACE_MODE', 'file'), 'clickhouseSpans': archived}))


def verify_kafka_clickhouse(values, trace_id):
    container = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + values['TIANMU_STACK_NAME'],
                     '--filter', 'label=com.docker.compose.service=clickhouse'], True)
    require(container and '\n' not in container, 'Expected one ClickHouse service')
    require(re.fullmatch(r'[0-9a-f]{32}', trace_id), 'Invalid trace ID')
    query = "SELECT kafka_partition, kafka_key, envelope_json FROM tianmu_trace.trace_spans_current WHERE trace_id='" + trace_id + "' FORMAT JSONEachRow"
    end = time.monotonic() + 40
    while time.monotonic() < end:
        records = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                                  'clickhouse-client --user trace_admin --password "$(cat /run/secrets/clickhouse-password)" --query "$1"', 'sh', query],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if records.returncode == 0:
            rows = []
            for line in records.stdout.decode('utf-8').splitlines():
                row = json.loads(line)
                row['event'] = json.loads(row['envelope_json'])
                rows.append(row)
            types = {r['event'].get('attributes', {}).get('span.type') for r in rows}
            if {'receive_query', 'agent_start', 'llm_inference', 'tool_call', 'finish'} <= types:
                require(all(r.get('kafka_key') == trace_id for r in rows), 'Kafka record key differs from traceId')
                require(len({r['kafka_partition'] for r in rows}) == 1, 'One trace spans multiple Kafka partitions')
                require(any(r['event'].get('attributes', {}).get('tool.name') == 'query_video_metrics'
                            and r['event']['attributes'].get('tool.success') is True for r in rows), 'Kafka metric tool span missing')
                return len(rows)
        time.sleep(0.25)
    raise RuntimeError('Kafka spans did not reach ClickHouse within 40 seconds')


def show_trace(values, trace_id):
    require(values.get('TRACE_MODE') == 'kafka', 'This release does not use Kafka/ClickHouse')
    require(trace_id and re.fullmatch(r'[0-9a-f]{32}', trace_id), 'Provide --trace-id as 32 lowercase hex characters')
    container = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + values['TIANMU_STACK_NAME'],
                     '--filter', 'label=com.docker.compose.service=clickhouse'], True)
    require(container and '\n' not in container, 'Expected one ClickHouse service')
    query = ("SELECT trace_id, span_name, span_type, status, start_time, round(duration_ns/1000000,3) AS duration_ms, "
             "kafka_partition, kafka_offset FROM tianmu_trace.trace_spans_current WHERE trace_id='" + trace_id + "' "
             "OR JSONExtractString(attributes_json,'run.trace_id')='" + trace_id + "' ORDER BY start_time FORMAT PrettyCompact")
    run(['docker', 'exec', container, 'sh', '-c',
         'clickhouse-client --user trace_admin --password "$(cat /run/secrets/clickhouse-password)" --query "$1"', 'sh', query])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'enable-kafka', 'preflight', 'build', 'up', 'status', 'verify', 'trace'])
    parser.add_argument('--env', type=Path)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--business', action='store_true')
    parser.add_argument('--trace-id')
    args = parser.parse_args()
    if args.action == 'init':
        require(args.directory, 'Provide an absolute private configuration directory')
        initialize(args.directory)
        return
    require(args.env and args.env.is_file(), 'Provide --env pointing to runtime.env or a release.env')
    values = read_env(args.env)
    if args.action == 'enable-kafka':
        enable_kafka(args.env)
    elif args.action == 'preflight':
        preflight(values, args.business)
    elif args.action == 'build':
        build(args.env, args.business)
    elif args.action == 'up':
        require(values.get('TIANMU_IMAGE_TAG') != 'unbuilt', 'Use a successfully built release.env')
        activate(args.env)
    elif args.action == 'status':
        run(compose(args.env, values) + ['ps'])
    elif args.action == 'verify':
        verify(values)
    elif args.action == 'trace':
        show_trace(values, args.trace_id)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Deployment failed: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
