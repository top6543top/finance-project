"""Render release manifests with strict values; does not contact a cluster."""
import argparse
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def render(kind, environment):
    image = environment.get('ACCOUNT_IMAGE', '')
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-f0-9]{64}', image):
        raise ValueError('ACCOUNT_IMAGE must be an immutable registry/repository@sha256 digest')
    substitutions = {'__IMAGE__': image}
    if kind == 'migration':
        name = environment.get('MIGRATION_JOB_NAME', '')
        if not re.fullmatch(r'account-migrate-[a-z0-9-]{1,40}', name):
            raise ValueError('Invalid MIGRATION_JOB_NAME')
        substitutions['__MIGRATION_JOB_NAME__'] = name
        paths = ['migration-job.yaml']
    else:
        host = environment.get('ACCOUNT_PUBLIC_HOST', '')
        if len(host) > 253 or not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}', host):
            raise ValueError('ACCOUNT_PUBLIC_HOST must be a DNS hostname')
        substitutions['__PUBLIC_HOST__'] = host
        paths = ['deployment.yaml', 'service.yaml', 'ingress.yaml', 'networkpolicy.yaml']
    documents = []
    for name in paths:
        document = (ROOT/'k8s'/name).read_text(encoding='utf-8')
        for token, value in substitutions.items():
            document = document.replace(token, value)
        if re.search(r'__[A-Z_]+__', document):
            raise ValueError(f'Unresolved template in {name}')
        documents.append(document)
    return '\n---\n'.join(documents)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['app', 'migration'], default='app')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        content = render(args.kind, os.environ)
    except ValueError as error:
        parser.error(str(error))
    args.output.write_text(content, encoding='utf-8')
