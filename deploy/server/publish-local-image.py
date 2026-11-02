#!/usr/bin/env python3
"""Promote a checksummed local image archive; never compile on the runner or server."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    component = os.environ['COMPONENT']
    repositories = {'backend': 'beidaomitu233/xinghui-admin-backend',
                    'web': 'beidaomitu233/xinghui-admin-vue3'}
    images = {'backend': 'ghcr.io/beidaomitu233/xinghui-admin-backend',
              'web': 'ghcr.io/beidaomitu233/xinghui-admin-vue3-web'}
    assert component in repositories
    repo, image = repositories[component], images[component]
    assert os.environ['GITHUB_REPOSITORY'] == repo
    sha, ci = os.environ['SOURCE_SHA'], os.environ['CI_RUN_ID']
    release, expected = os.environ['ARTIFACT_RELEASE'], os.environ['ARCHIVE_SHA256']
    assert re.fullmatch(r'[0-9a-f]{40}', sha)
    assert re.fullmatch(r'[0-9]+', ci)
    assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', release)
    assert re.fullmatch(r'[0-9a-f]{64}', expected)
    tag = image + ':ci-' + ci + '-' + sha
    asset = 'image-' + component + '-' + sha + '.tar.gz'
    with tempfile.TemporaryDirectory(prefix='xinghui-local-image-') as directory:
        subprocess.run(['gh', 'release', 'download', release, '--repo', repo,
                        '--pattern', asset, '--dir', directory], check=True)
        archive = Path(directory) / asset
        assert archive.is_file()
        with archive.open('rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, 'archive checksum mismatch'
        subprocess.run(['docker', 'load', '--input', str(archive)], check=True)
        info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag], text=True))[0]
        labels = info['Config'].get('Labels') or {}
        assert info['Os'] == 'linux' and info['Architecture'] == 'amd64'
        assert labels.get('org.opencontainers.image.source') == 'https://github.com/' + repo
        assert labels.get('org.opencontainers.image.revision') == sha
        assert labels.get('io.xinghui.ci-run') == ci
        try:
            subprocess.run(['docker', 'login', 'ghcr.io', '--username', os.environ['GITHUB_ACTOR'],
                            '--password-stdin'], input=os.environ['REGISTRY_TOKEN'] + '\n', text=True, check=True)
            subprocess.run(['docker', 'push', tag], check=True)
        finally:
            subprocess.run(['docker', 'logout', 'ghcr.io'], check=False)
        info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag], text=True))[0]
        digests = [value for value in info.get('RepoDigests', [])
                   if re.fullmatch(re.escape(image) + r'@sha256:[0-9a-f]{64}', value)]
        assert len(digests) == 1, 'ambiguous published digest'
        digest = digests[0].split('@', 1)[1]
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('digest=' + digest + '\n')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write(f'Local artifact: `{asset}`\n\nSHA256: `{expected}`\n\nPublished: `{digests[0]}`\n')


if __name__ == '__main__':
    main()
