#!/usr/bin/env python3
"""Signs in to Trakt for the picks: writes trakt_access_token and trakt_refresh_token to config.properties.

    python3 tools/trakt_login.py

Needs `trakt_client_id` in config.properties (an app made at https://app.trakt.tv/settings/apps, redirect URI
urn:ietf:wg:oauth:2.0:oob). It shows a code; enter it at the address it shows, signed in to your Trakt account, then
build and install again. The tokens are yours and secret like the TMDB key: they stay in config.properties, and this
never prints them.

The TV refreshes the token by itself every week and keeps the new one (refresh tokens are single-use), so this runs
once; run it again only if the picks stop coming from Trakt (the log says "Trakt rejected credentials").
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, 'config.properties')
AUTH = 'https://auth.trakt.tv'


def prop(name):
    value = ''
    with open(CONFIG, encoding='utf-8') as f:
        for line in f:
            key, eq, rest = line.partition('=')
            if eq and key.strip() == name and not key.lstrip().startswith('#'):
                value = rest.strip().strip('"')
    return value


def save(values):
    """Sets each key in config.properties (in place where it is, at the end where it isn't), keeping the rest."""
    with open(CONFIG, encoding='utf-8') as f:
        lines = f.read().splitlines()
    todo = dict(values)
    for i, line in enumerate(lines):
        key, eq, _ = line.partition('=')
        if eq and key.strip() in todo and not key.lstrip().startswith('#'):
            lines[i] = '%s=%s' % (key.strip(), todo.pop(key.strip()))
    lines += ['%s=%s' % kv for kv in todo.items()]
    tmp = CONFIG + '.tmp'
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    os.replace(tmp, CONFIG)


def post(path, body, client_id):
    request = urllib.request.Request(AUTH + path, data=json.dumps(body).encode(), method='POST', headers={
        'Content-Type': 'application/json',
        'trakt-api-version': '2',
        'trakt-api-key': client_id,
        'User-Agent': 'FreeMyTV/1.0',
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}


def main():
    if not os.path.isfile(CONFIG):
        sys.exit('No config.properties: run ./build.sh once to create it.')
    client_id = prop('trakt_client_id')
    if not client_id:
        sys.exit('Add trakt_client_id to config.properties first (https://app.trakt.tv/settings/apps).')
    status, device = post('/oauth/device/code', {'client_id': client_id}, client_id)
    if status != 200:
        sys.exit('Trakt refused the client ID (HTTP %d): check trakt_client_id.' % status)
    print('Open %s and enter %s' % (device.get('verification_url') or AUTH + '/activate', device['user_code']),
          flush=True)
    interval = max(1, int(device.get('interval') or 5))
    deadline = time.time() + int(device.get('expires_in') or 600)
    while time.time() < deadline:
        time.sleep(interval)
        status, token = post('/oauth/device/token', {'code': device['device_code'], 'client_id': client_id}, client_id)
        if status == 200 and token.get('access_token'):
            save({'trakt_access_token': token['access_token'], 'trakt_refresh_token': token.get('refresh_token', '')})
            print('Signed in. Saved to config.properties: now ./build.sh and install again.')
            return
        if status == 429:
            interval += 1   # polling too fast
        elif status in (404, 409, 410, 418):   # bad code, already used, expired, denied
            sys.exit('Trakt sign-in failed (HTTP %d). Run this again.' % status)
    sys.exit('The code expired. Run this again.')


if __name__ == '__main__':
    main()
