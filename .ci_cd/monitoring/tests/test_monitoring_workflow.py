#!/usr/bin/env python3
"""Local workflow checks; fake AWS calls, no SSM invocation or Docker lifecycle changes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = yaml.safe_load((ROOT / '.github/workflows/monitoring-deploy.yml').read_text())
STEPS = WORKFLOW['jobs']['monitoring']['steps']
REVISION = 'b' * 40

AWS = r'''import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as log:
    log.write(json.dumps(args) + '\n')
scenario = os.environ['SCENARIO']
if args[:2] == ['sts', 'get-caller-identity']:
    print(json.dumps({'Account': 'wrong' if scenario == 'identity' else '123456789012',
                      'Arn': 'wrong' if scenario == 'role' else 'arn:aws:sts::123456789012:assumed-role/monitoring-staging/fixture'}))
elif args[:2] == ['s3api', 'put-object']:
    for flag, value in {'--bucket': 'openremote-monitoring-123456789012-eu-west-1',
                        '--key': 'staging/releases/' + os.environ['OPERATION'] + '-123-1/bundle.tar.gz',
                        '--expected-bucket-owner': '123456789012', '--if-none-match': '*',
                        '--region': 'eu-west-1', '--server-side-encryption': 'AES256'}.items():
        assert args[args.index(flag) + 1] == value
    print(json.dumps({'VersionId': 'null' if scenario == 'version' else ('v' * 1025 if scenario == 'long-version' else 'version.+/=')}))
elif args[:2] == ['ssm', 'send-command']:
    assert os.environ['AWS_MAX_ATTEMPTS'] == '1'
    assert args[args.index('--document-name') + 1] == 'openremote-monitoring-staging-deploy'
    assert args[args.index('--document-version') + 1] == '1'
    assert args[args.index('--instance-ids') + 1] == 'i-0123456789abcdef0'
    assert json.loads(args[args.index('--parameters') + 1]) == {
        'Action': [os.environ['OPERATION']], 'ReleaseId': [os.environ['OPERATION'] + '-123-1'], 'VersionId': ['version.+/='],
        'SHA256': ['a' * 64], 'CreateState': [os.environ['CREATE_STATE']], 'EnableTraces': [os.environ['ENABLE_TRACES']]}
    print('fixture-command-id')
elif args[:3] == ['ssm', 'wait', 'command-executed']:
    sys.exit(0 if scenario in ('success', 'success-base', 'deploy', 'delayed-success') else 1)
elif args[:2] == ['ssm', 'get-command-invocation']:
    assert args[args.index('--plugin-name') + 1] == 'monitoringDeploy'
    if scenario == 'unavailable':
        sys.exit(1)
    good = scenario in ('success', 'success-base', 'deploy', 'delayed-success')
    if scenario == 'delayed-success':
        with open(os.environ['CALLS']) as log:
            gets = sum(json.loads(line)[:2] == ['ssm', 'get-command-invocation'] for line in log)
        if gets == 1:
            print(json.dumps({'Status': 'InProgress', 'ResponseCode': -1}))
            sys.exit(0)
    print(json.dumps({'Status': 'Success' if good else ('InProgress' if scenario == 'running' else 'Failed'),
                      'ResponseCode': 0 if good else 1,
                      'StandardOutputContent': 'PASS host action completed; temporary bundle removed' if good else '',
                      'StandardErrorContent': '' if good else 'Unmanaged project container'}))
else:
    raise AssertionError(args)
'''


class MonitoringWorkflowTest(unittest.TestCase):
    def test_structure_and_shell_syntax(self):
        job = WORKFLOW['jobs']['monitoring']
        self.assertEqual(job['environment'], 'monitoring-staging')
        self.assertEqual(WORKFLOW['concurrency'], {'group': '${{ github.repository }}-monitoring-staging', 'cancel-in-progress': False})
        triggers = WORKFLOW.get('on', WORKFLOW.get(True))
        self.assertEqual(set(triggers), {'workflow_call'})
        self.assertNotIn('secrets', triggers['workflow_call'])
        self.assertEqual(triggers['workflow_call']['inputs']['operation']['default'], 'plan')
        self.assertEqual(job['permissions'], {'contents': 'read', 'id-token': 'write'})
        for step in STEPS:
            if 'run' in step:
                subprocess.run(['/bin/bash', '-n'], input=step['run'], text=True, check=True)
            if 'uses' in step:
                self.assertRegex(step['uses'], r'@[0-9a-f]{40}$')
            if step.get('uses', '').startswith('actions/checkout@'):
                self.assertFalse(step['with']['persist-credentials'])

    def test_settings_reject_invalid_inputs(self):
        script = STEPS[0]['run']
        settings = dict(OPERATION='plan', TRIGGER_EVENT='push', CORE_REVISION=REVISION, EXPECTED_ACCOUNT_ID='123456789012',
                        ROLE_ARN='arn:aws:iam::123456789012:role/monitoring-staging',
                        AWS_REGION='eu-west-1', INSTANCE_ID='i-0123456789abcdef0',
                        DOCUMENT_NAME='openremote-monitoring-staging-deploy', DOCUMENT_VERSION='2',
                        COMPOSE_PROJECT='alloy-customer-staging', CONFIGURATION_FILE='.ci_cd/monitoring/staging.env',
                        CREATE_STATE='true', ENABLE_TRACES='false')
        cases = [(None, None), ('CORE_REVISION', 'main'), ('EXPECTED_ACCOUNT_ID', '999999999999'),
                 ('ROLE_ARN', 'arn:aws:iam::123456789012:role/application'),
                 ('DOCUMENT_VERSION', '$LATEST'), ('INSTANCE_ID', 'i-wrong'),
                 ('CONFIGURATION_FILE', '../outside.env'), ('CONFIGURATION_FILE', '/tmp/secret'),
                 ('COMPOSE_PROJECT', '-wrong'), ('CREATE_STATE', 'yes'),
                 ('ENABLE_TRACES', 'true;echo bad'), ('OPERATION', 'deploy'), ('OPERATION', 'rollback')]
        for key, value in cases:
            with self.subTest(key=key):
                env = dict(os.environ, **settings)
                if key:
                    env[key] = value
                result = subprocess.run(['/bin/bash'], input=script, env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode == 0, key is None, result.stderr)

    def test_manual_deploy_gate(self):
        # Execute the actual preflight before the upload script: non-manual deploy
        # and invalid operations must never reach any AWS call.
        validation = STEPS[0]['run']
        for event in ('push', 'pull_request', 'workflow_dispatch'):
            env = dict(os.environ, OPERATION='deploy', TRIGGER_EVENT=event,
                       CORE_REVISION=REVISION, EXPECTED_ACCOUNT_ID='123456789012',
                       ROLE_ARN='arn:aws:iam::123456789012:role/monitoring-staging',
                       AWS_REGION='eu-west-1', INSTANCE_ID='i-0123456789abcdef0',
                       DOCUMENT_NAME='openremote-monitoring-staging-deploy', DOCUMENT_VERSION='1',
                       COMPOSE_PROJECT='alloy-customer-staging', CONFIGURATION_FILE='.ci_cd/monitoring/staging.env',
                       CREATE_STATE='true', ENABLE_TRACES='true')
            result = subprocess.run(['/bin/bash'], input=validation, env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode == 0, event == 'workflow_dispatch', result.stderr)

    def test_bundle_has_only_required_files(self):
        script = next(step['run'] for step in STEPS if step.get('id') == 'bundle')
        required = {'deploy.py', 'compose.yaml', 'compose.traces.yaml', 'customer.env',
                    'config/logs.alloy', 'config/host-metrics.alloy', 'config/traces.alloy'}
        for traces in ('false', 'true'):
            with tempfile.TemporaryDirectory() as temporary:
                work = Path(temporary)
                source = work / 'core/.ci_cd/monitoring'
                (source / 'config').mkdir(parents=True)
                for name in required - {'customer.env'}:
                    (source / name).write_text('fixture ' + name)
                (source / 'credentials').mkdir()
                (source / 'credentials/loki_password').write_text('must-not-be-packaged')
                config = work / 'project/.ci_cd/monitoring'
                config.mkdir(parents=True)
                shutil.copyfile(ROOT / '.ci_cd/monitoring/customer.env.example', config / 'staging.env')
                binary = work / 'bin'
                binary.mkdir()
                (binary / 'git').write_text('#!/bin/sh\nprintf "%s\\n" "$CORE_REVISION"\n')
                (binary / 'git').chmod(0o700)
                (binary / 'docker').write_text('#!/bin/sh\ntest "$1 $2" = "compose --env-file"\ncase "$*" in *"config --quiet") exit 0;; *) exit 1;; esac\n')
                (binary / 'docker').chmod(0o700)
                (binary / 'sha256sum').write_text('#!' + sys.executable + '\nimport hashlib,sys\nprint(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest(),sys.argv[1])\n')
                (binary / 'sha256sum').chmod(0o700)
                env = dict(os.environ, PATH=str(binary) + ':' + os.environ['PATH'], RUNNER_TEMP=str(work),
                           GITHUB_SHA=REVISION, CORE_REVISION=REVISION, ENABLE_TRACES=traces,
                           COMPOSE_PROJECT='alloy-customer-staging', CONFIGURATION_FILE='.ci_cd/monitoring/staging.env', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                           GITHUB_OUTPUT=str(work / 'output'), GITHUB_STEP_SUMMARY=str(work / 'summary'), COPYFILE_DISABLE='1')
                result = subprocess.run(['/bin/bash'], input=script, cwd=work, env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                bundle = work / 'monitoring-123-1/bundle.tar.gz'
                with tarfile.open(bundle) as archive:
                    self.assertEqual(set(archive.getnames()), required)
                    self.assertEqual(archive.extractfile('customer.env').read(), (config / 'staging.env').read_bytes())
                self.assertIn(hashlib.sha256(bundle.read_bytes()).hexdigest(), (work / 'output').read_text())

    def test_transfer_and_result_handling(self):
        script = STEPS[-1]['run']
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            stub = work / 'aws'
            stub.write_text('#!' + sys.executable + '\n' + AWS)
            stub.chmod(0o700)
            for scenario in ('success', 'success-base', 'deploy', 'delayed-success', 'identity', 'role', 'version', 'long-version', 'blocked', 'running', 'unavailable'):
                with self.subTest(scenario=scenario):
                    log = work / 'calls'
                    log.write_text('')
                    env = dict(os.environ, PATH=str(work) + ':' + os.environ['PATH'], CALLS=str(log), SCENARIO=scenario,
                               OPERATION='deploy' if scenario in ('deploy', 'delayed-success') else 'plan',
                               BUNDLE_PATH=str(work / 'bundle.tar.gz'), BUNDLE_SHA256='a' * 64,
                               GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1', GITHUB_STEP_SUMMARY=str(work / 'summary'),
                               EXPECTED_ACCOUNT_ID='123456789012', CREATE_STATE='false' if scenario == 'success-base' else 'true',
                               ENABLE_TRACES='false' if scenario == 'success-base' else 'true',
                               AWS_REGION='eu-west-1', INSTANCE_ID='i-0123456789abcdef0',
                               DOCUMENT_NAME='openremote-monitoring-staging-deploy', DOCUMENT_VERSION='1')
                    result = subprocess.run(['/bin/bash'], input=script, cwd=work, env=env,
                                            text=True, capture_output=True, timeout=20)
                    self.assertEqual(result.returncode == 0, scenario in ('success', 'success-base', 'deploy', 'delayed-success'), result.stdout + result.stderr)
                    calls = [json.loads(line) for line in log.read_text().splitlines()]
                    sends = [call for call in calls if call[:2] == ['ssm', 'send-command']]
                    self.assertEqual(len(sends), 0 if scenario in ('identity', 'role', 'version', 'long-version') else 1)
                    gets = [call for call in calls if call[:2] == ['ssm', 'get-command-invocation']]
                    if scenario == 'running':
                        self.assertEqual(len(gets), 13)
                    if scenario == 'delayed-success':
                        self.assertEqual(len(gets), 2)
                    if scenario in ('identity', 'role'):
                        self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
