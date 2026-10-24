"""Offline checks for deployment configuration and release boundaries; no Docker daemon or credentials needed."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('deployment', ROOT / 'deploy' / 'manage.py')
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class DeploymentContract(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_template_is_nonsecret_and_uses_a_dedicated_stack(self):
        values = deployment.read_env(ROOT / 'deploy' / '.env.example')
        self.assertEqual('tianmu-staging', values['TIANMU_STACK_NAME'])
        self.assertEqual('unbuilt', values['TIANMU_IMAGE_TAG'])

    def test_secret_literals_and_shell_execution_settings_are_rejected(self):
        for line in ['AGENT_DB_PASSWORD=private', 'PATH=/tmp', 'DEEPSEEK_API_KEY=private',
                     'AGENT_MINIO_ACCESS_KEY=private', 'AGENT_MINIO_SECRET_KEY=private']:
            file = self.root / 'runtime.env'
            file.write_text(line)
            with self.assertRaises(RuntimeError):
                deployment.read_env(file)

    def test_release_cannot_expand_shell_or_compose_variables(self):
        with self.assertRaises(RuntimeError):
            deployment.write_env(self.root / 'release.env', {'TIANMU_IMAGE_TAG': '${UNTRUSTED}'})

    def test_compose_uses_the_release_snapshot_instead_of_the_current_checkout(self):
        file = self.root / 'release.env'
        file.write_text('')
        (self.root / 'manifest.json').write_text('{}')
        command = deployment.compose(file, {'TIANMU_STACK_NAME': 'test-stack', 'DEPLOYMENT_MODE': 'business'})
        self.assertIn(str(self.root / 'compose.yaml'), command)
        self.assertIn(str(self.root / 'compose.business.yaml'), command)

    def test_shell_environment_cannot_override_locked_compose_images(self):
        with patch.dict(os.environ, {'MYSQL_IMAGE': 'unexpected:latest'}):
            with patch.object(deployment.subprocess, 'check_call') as call:
                deployment.run(['docker', 'compose', 'config', '--quiet'])
                self.assertNotIn('MYSQL_IMAGE', call.call_args.kwargs['env'])

    def test_retagged_image_cannot_be_activated(self):
        values = deployment.read_env(ROOT / 'deploy' / '.env.example')
        values.update(TIANMU_IMAGE_TAG='release-001', DEPLOYMENT_MODE='staging')
        file = self.root / 'release.env'
        deployment.write_env(file, values)
        (self.root / 'manifest.json').write_text(json.dumps({'image': 'tianmu-agent:release-001', 'mode': 'staging',
                                                           'imageId': 'sha256:expected', 'upstreamImages': {}}))
        with patch.object(deployment, 'preflight'), patch.object(deployment, 'run', return_value='sha256:replaced') as run:
            with self.assertRaisesRegex(RuntimeError, 'replaced'):
                deployment.activate(file)
            self.assertEqual(1, run.call_count)

    def test_kafka_settings_require_stable_cluster_id_and_safe_topic(self):
        values = deployment.read_env(ROOT / 'deploy' / '.env.example')
        values.update(TRACE_MODE='kafka', KAFKA_CLUSTER_ID='4L6g3nShT-eMCtK--X86sw')
        for topic in ['..', 'trace;touch /tmp/x', 'trace/invalid']:
            file = self.root / ('invalid-' + str(len(topic)) + '.env')
            deployment.write_env(file, dict(values, KAFKA_TRACE_TOPIC=topic))
            with self.assertRaisesRegex(RuntimeError, 'topic'):
                deployment.read_env(file)

    def test_kafka_overlay_is_part_of_the_release_snapshot(self):
        file = self.root / 'release.env'
        (self.root / 'manifest.json').write_text('{}')
        command = deployment.compose(file, {'TIANMU_STACK_NAME': 'test-stack', 'TRACE_MODE': 'kafka'})
        self.assertIn(str(self.root / 'compose.kafka.yaml'), command)

    def test_trace_mode_cannot_be_changed_after_build(self):
        values = deployment.read_env(ROOT / 'deploy' / '.env.example')
        values.update(TIANMU_IMAGE_TAG='release-002', DEPLOYMENT_MODE='staging')
        file = self.root / 'release.env'
        deployment.write_env(file, values)
        (self.root / 'manifest.json').write_text(json.dumps({'image': 'tianmu-agent:release-002', 'mode': 'staging',
                                                           'traceMode': 'kafka'}))
        with patch.object(deployment, 'preflight'), patch.object(deployment, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'trace mode'):
                deployment.activate(file)
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
