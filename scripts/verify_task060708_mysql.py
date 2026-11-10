#!/usr/bin/env python3
"""TASK06/07/08 real MySQL + built jar checks; no workflow implementation or T acceptance promotion."""
from __future__ import annotations
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from verify_task02_mysql import BACKEND, MIGRATION_DIR, ROOT, IMAGE, MysqlHarness, docker, find_java, require
from verify_task03_mysql import Application, Reply, expect, parallel_calls

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6RkAAAAASUVORK5CYII=')

def key():
    return str(uuid.uuid4())


def binary_request(app, method, path, token=None, data=b'', content_type=None):
    headers = {'Content-Length': str(len(data))}
    if content_type:
        headers['Content-Type'] = content_type
    if token:
        headers['Authorization'] = 'Bearer ' + token
    conn = http.client.HTTPConnection('127.0.0.1', app.port, timeout=45)
    try:
        conn.request(method, '/api/v1' + path, data, headers)
        response = conn.getresponse()
        payload = response.read()
        app.requests += 1
        parsed = payload
        if 'application/json' in (response.getheader('Content-Type') or ''):
            parsed = json.loads(payload)
            if 200 <= response.status < 300:
                require(set(parsed) == {'data', 'requestId', 'serverTime'}, 'upload success envelope missing')
                require(parsed['requestId'] == response.getheader('X-Request-Id'), 'upload requestId mismatch')
                parsed = parsed['data']
                app.envelopes += 1
        return Reply(response.status, parsed, response.getheaders(), method + ' ' + path)
    finally:
        conn.close()


def upload(app, token, content=PNG, name='receipt.png', mime='image/png'):
    boundary = 'isolated' + secrets.token_hex(12)
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f'Content-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return binary_request(app, 'POST', '/files', token, body, 'multipart/form-data; boundary=' + boundary)


def verify(h, app):
    app.prepare_jar()
    with zipfile.ZipFile(app.runtime_jar) as archive:
        for migration in sorted(MIGRATION_DIR.glob('V*.sql')):
            require(archive.read('BOOT-INF/classes/db/migration/' + migration.name) == migration.read_bytes(), 'stale jar migration')
    h.start()
    state = {'password': secrets.token_urlsafe(24)}

    def sql(statement):
        result = h.mysql(statement)
        require(result.returncode == 0, 'isolated SQL fixture failed; raw statement withheld')

    def req(method, path, body=None, actor='owner', request_key=None):
        return app.request(method, path, body, token=state[actor + '_token'],
                           extra=[('Idempotency-Key', request_key)] if request_key else None)

    def create(kind='PURCHASE', payload=None):
        data = expect(req('POST', '/applications', {'type': kind}, request_key=key()), 201).data
        aid = data.get('applicationId', data.get('id'))
        require(isinstance(aid, str) and aid.isdigit(), 'application ID not decimal string')
        if payload is not None:
            save(aid, payload)
        return aid

    def version(aid):
        return h.scalar(f'SELECT CAST(row_version AS CHAR) FROM application WHERE id={aid}')

    def save(aid, payload, status=200):
        return expect(req('PUT', f'/applications/{aid}/draft', {'expectedVersion': version(aid),
            'title': 'Isolated verification', 'remark': '', 'payload': payload}, request_key=key()), status)

    def validate(aid, status=200):
        return expect(req('POST', f'/applications/{aid}/validate', {'expectedVersion': version(aid)}), status)

    def bind(aid, fid, purpose='GENERAL', target=None, receipt=None, request_key=None, expected_version=None):
        body = {'expectedVersion': expected_version or version(aid), 'fileId': fid, 'purpose': purpose}
        if target is not None:
            body['draftTargetKey'] = target
        if receipt is not None:
            body['receiptNumber'] = receipt
        return req('POST', f'/applications/{aid}/bindings', body, request_key=request_key or key())

    def setup():
        result = subprocess.run([str(h.java), '-jar', str(app.runtime_jar), '--spring.profiles.active=db,migrate',
            '--spring.main.web-application-type=none'], cwd=ROOT,
            env=h.app_env('approval', 'approval_migrator', h.migration_password, demo=False),
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
        require(result.returncode == 0, 'fresh V1-latest migration failed; raw logs withheld')
        app.start()
        for who in ('owner', 'other', 'admin'):
            sql('DELETE FROM rate_limit_bucket')
            state[who + '_id'] = expect(app.request('POST', '/native-auth/register', {
                'username': 'fixture_' + who, 'name': 'Isolated Fixture', 'email': who + '@example.test',
                'password': state['password'], 'requestedDepartmentId': '1'}), 201).data['userId']
            uid = state[who + '_id']
            sql(f"UPDATE user SET status='ACTIVE',department_id=1 WHERE id={uid}")
            role = 'SYS_ADMIN' if who == 'admin' else 'EMPLOYEE'
            sql(f"INSERT INTO user_role(user_id,role_id,created_at) SELECT {uid},id,UTC_TIMESTAMP(6) FROM role WHERE code='{role}'")
            state[who + '_token'] = expect(app.request('POST', '/native-auth/login', {
                'username': 'fixture_' + who, 'password': state['password']}), 200).data['token']
        return 'fresh MySQL8 V1-latest migration; runtime approval_app; owner/outsider/admin SQL identity fixtures only'
    h.check('isolated migration and startup', setup)

    def private_uploads():
        file = expect(upload(app, state['owner_token']), 201).data
        fid = file['fileId']
        state['file'] = file
        require(file['byteSize'] == str(len(PNG)), 'size must be string')
        require(file['sha256'] == hashlib.sha256(PNG).hexdigest(), 'digest mismatch')
        require(h.scalar(f"SELECT status FROM file_object WHERE id={fid}") == 'READY', 'not READY')
        require(h.scalar(f"SELECT upload_lease_until IS NULL FROM file_object WHERE id={fid}") == '1', 'lease not cleared')
        reply = expect(binary_request(app, 'GET', f'/files/{fid}/preview', state['owner_token']), 200)
        require(reply.data == PNG, 'private bytes mismatch')
        require(reply.header('X-Content-Type-Options') == 'nosniff', 'nosniff missing')
        require(reply.header('Cache-Control') == 'no-store', 'private file cached')
        expect(binary_request(app, 'GET', f'/files/{fid}/download'), 401)
        for who in ('other', 'admin'):
            expect(binary_request(app, 'GET', f'/files/{fid}/download', state[who + '_token']), 404)
        require(expect(upload(app, state['owner_token'], PNG + b'x' * (1024 * 1024)), 201).data['byteSize'] == str(len(PNG) + 1024 * 1024), '1MiB JSON limit leaked into upload')
        return 'private READY bytes+sha256+string sizes; >1MiB streamed; anonymous/outsider/SYS_ADMIN denied; no-store/nosniff'
    h.check('private streaming upload and access control', private_uploads)

    def invalid_uploads():
        for content, name, mime in [(PNG, 'x.exe', 'image/png'), (b'not png', 'x.png', 'image/png'),
                                    (PNG, 'x.png', 'application/pdf'), (PNG, '../x.png', 'image/png')]:
            reply = upload(app, state['owner_token'], content, name, mime)
            require(reply.status in (400, 415), f'invalid upload accepted/status={reply.status}')
        expect(upload(app, state['owner_token'], PNG + b'x' * (10 * 1024 * 1024)), 413)
        require(h.scalar("SELECT COUNT(*) FROM file_object WHERE status='READY' AND byte_size>10485760") == '0', 'oversized READY persisted')
        for content, name, mime in [(b'\xff\xd8\xff\xe0' + b'jpeg-header-fixture', 'photo.JPG', 'image/jpeg'), (b'%PDF-1.7\nfixture', 'quote.pdf', 'application/pdf')]:
            file = expect(upload(app, state['owner_token'], content, name, mime), 201).data
            require(file['mediaType'] == mime, 'allowed format media mismatch')
            response = expect(binary_request(app, 'GET', f"/files/{file['fileId']}/preview", state['owner_token']), 200)
            if mime == 'application/pdf':
                require(response.header('Content-Disposition').startswith('attachment'), 'active PDF preview allowed inline')
        boundary = 'isolated' + secrets.token_hex(12)
        part = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="x.png"\r\nContent-Type: image/png\r\n\r\n').encode() + PNG + b'\r\n'
        body = part + part + f'--{boundary}--\r\n'.encode()
        expect(binary_request(app, 'POST', '/files', state['owner_token'], body, 'multipart/form-data; boundary=' + boundary), 400)
        return 'extension/MIME/header/path mismatch, extra multipart part and >10MiB rejected; JPG/PNG/PDF header fixtures accepted; PDF preview forced attachment; no oversized READY rows'
    h.check('upload validation and byte bound', invalid_uploads)

    def bindings():
        aid = create()
        state['binding_application'] = aid
        fid = state['file']['fileId']
        ver, request_key = version(aid), key()
        first = expect(bind(aid, fid, request_key=request_key, expected_version=ver), 200).data
        replay = expect(bind(aid, fid, request_key=request_key, expected_version=ver), 200).data
        require(first == replay and int(version(aid)) == int(ver) + 1, 'binding replay incremented version')
        expect(bind(aid, fid), 409)
        copy = expect(upload(app, state['owner_token']), 201).data
        expect(bind(aid, copy['fileId']), 409)
        expect(bind(aid, copy['fileId'], request_key=request_key, expected_version=ver), 409)
        expect(binary_request(app, 'GET', f'/files/{fid}/preview', state['owner_token']), 404)
        expect(binary_request(app, 'GET', f'/files/{fid}/preview?applicationId={aid}', state['owner_token']), 200)
        expect(binary_request(app, 'GET', f'/files/{fid}/preview?applicationId={aid}', state['admin_token']), 404)
        state['binding_id'] = first['bindingId']
        return 'bind+version+success replay; same object/hash duplicate and changed idempotency fingerprint409; bound access requires current application context'
    h.check('binding idempotency deduplication and bound context', bindings)

    purchase = {'reason': 'Office equipment', 'expectedDate': '2026-12-01', 'project': 'Project A',
        'budget': '20.00', 'freightAmount': '1.00', 'otherAmount': '0.00',
        'items': [{'clientKey': 'item1', 'name': 'Paper', 'specification': 'A4', 'quantity': '1.005', 'unit': 'box', 'unitPrice': '5.00'},
                  {'clientKey': 'item2', 'name': 'Folder', 'specification': 'A4', 'quantity': '1.004', 'unit': 'box', 'unitPrice': '5.00'}],
        'quotes': [{'clientKey': 'quote1', 'supplier': 'Supplier A', 'quoteDate': '2026-11-10', 'total': '11.05', 'sourceType': 'OFFLINE', 'basisText': 'Written offer'},
                   {'clientKey': 'quote2', 'supplier': 'Supplier B', 'quoteDate': '2026-11-10', 'total': '12.00', 'sourceType': 'OFFLINE', 'basisText': 'Written offer B'}],
        'selectedQuoteKey': 'quote1', 'selectionReason': 'Best fit for the project'}

    def purchase_validation():
        aid = create(payload=purchase)
        result = validate(aid).data
        state['purchase_result'] = result
        require(result['amount'] == '11.05', 'server did not recompute rounded line plus freight')
        before = version(aid)
        validate(aid)
        require(version(aid) == before, 'read-only validate mutated application version')
        broken = json.loads(json.dumps(purchase)); broken['budget'] = '10.00'
        save(aid, broken)
        error = validate(aid, 400)
        require(any(field['path'] == 'payload.budget' for field in error.data['fieldErrors']), 'budget validation lacks exact field path')
        broken['budget'] = '20.00'; broken['selectedQuoteKey'] = 'quote2'
        save(aid, broken)
        validate(aid, 400)
        broken['quotes'][1]['total'] = '11.05'
        broken['quotes'][0]['total'] = '11.04'
        save(aid, broken); validate(aid, 400)  # Selected total closes but is not the minimum.
        broken['nonLowestReason'] = 'Better warranty despite a higher quote'
        save(aid, broken); validate(aid)
        broken['quotes'][0]['total'] = '11.05'
        broken['quotes'] = broken['quotes'][:1]; broken['selectedQuoteKey'] = 'quote1'
        save(aid, broken); validate(aid, 400)
        broken['singleSourceReason'] = 'Only licensed supplier'
        save(aid, broken); validate(aid)
        expect(req('POST', f'/applications/{aid}/validate', {'expectedVersion': '1'}), 409)
        expect(req('POST', f'/applications/{aid}/validate', {'expectedVersion': version(aid)}, actor='other'), 404)
        return 'purchase row HALF_UP sum11.05, budget and whole quote mismatch; quote minimum/single source; field errors; read-only version and authorization'
    h.check('purchase complete validation and money recomputation', purchase_validation)

    travel = {'origin': 'Hangzhou', 'destination': 'Shanghai', 'startAt': '2026-11-10T15:59:00Z',
        'endAt': '2026-11-10T16:01:00Z', 'purpose': 'Meeting', 'transport': 'TRAIN', 'budget': '20000.00'}

    def travel_validation():
        aid = create('TRAVEL', travel)
        result = validate(aid).data
        require(str(result['naturalDays']) == '2', 'travel must count Beijing natural days')
        require(result['amount'] == '20000.00', 'travel budget amount wrong')
        altered = dict(travel, endAt=travel['startAt']); save(aid, altered)
        require(str(validate(aid).data['naturalDays']) == '1', 'same day must count1')
        altered['transport'] = 'OTHER'; save(aid, altered); validate(aid, 400)
        altered['transportOtherReason'] = 'Chartered vehicle'; save(aid, altered); validate(aid)
        altered['endAt'] = '2026-11-09T00:00:00Z'; save(aid, altered); validate(aid, 400)
        altered.update(startAt='2026-01-31T15:00:00Z', endAt='2026-02-01T02:00:00Z')
        save(aid, altered)
        require(str(validate(aid).data['naturalDays']) == '2', 'cross-month Beijing dates wrong')
        return 'UTC-Z start/end; Beijing midnight2days/same-day1; end-before-start and OTHER without reason rejected; budget20000 no flow execution'
    h.check('travel dates and transport validation', travel_validation)

    def reimbursement_validation():
        payload = {'description': 'Independent expense', 'independentReason': 'No prior application',
            'items': [{'clientKey': 'expense1', 'type': 'OFFICE', 'expenseDate': '2026-11-10', 'purpose': 'Paper', 'amount': '11.05',
                       'noReceipt': True, 'noReceiptReason': 'Receipt lost'}]}
        aid = create('REIMBURSEMENT', payload)
        require(validate(aid).data['amount'] == '11.05', 'expense sum wrong')
        payload['items'][0]['noReceipt'] = False; payload['items'][0]['noReceiptReason'] = ''
        save(aid, payload); validate(aid, 400)
        file = expect(upload(app, state['owner_token'], PNG + b'receipt unique'), 201).data
        expect(bind(aid, file['fileId'], 'EXPENSE_RECEIPT', 'expense1', 'INV-001'), 200)
        validate(aid)
        second_file = expect(upload(app, state['owner_token'], PNG + b'second receipt'), 201).data
        expect(bind(aid, second_file['fileId'], 'EXPENSE_RECEIPT', 'expense1', 'INV-002'), 200)
        payload['items'].extend([
            {'clientKey': 'expense2', 'type': 'MEALS', 'expenseDate': '2026-11-10', 'purpose': 'Lunch', 'amount': '5.00', 'noReceipt': True, 'noReceiptReason': 'Lost'},
            {'clientKey': 'expense3', 'type': 'TRANSPORT', 'expenseDate': '2026-11-10', 'purpose': 'Bus', 'amount': '2.00', 'noReceipt': True, 'noReceiptReason': 'No paper receipt'}])
        save(aid, payload)
        validated = validate(aid).data
        require(validated['amount'] == '18.05', 'three expense rows not summed')
        receipts = validated['formSnapshot']['bindings']
        require(len(receipts) == 2 and {entry['receiptNumber'] for entry in receipts} == {'INV-001', 'INV-002'}, 'receipt numbers overwritten or not frozen per binding')
        require(all(entry['draftTargetKey'] == 'expense1' for entry in receipts), 'multiple receipts lost their expense target')
        payload['items'][0]['noReceipt'] = True; payload['items'][0]['noReceiptReason'] = 'Cannot coexist'
        save(aid, payload); validate(aid, 400)
        return 'reimbursement independent reason+sum; missing receipt rejected; per-binding receipt number; noReceipt and receipt mutually exclusive'
    h.check('reimbursement receipts and independent validation', reimbursement_validation)

    def related_snapshots():
        source = create('TRAVEL', travel)
        snapshot = validate(source).data['formSnapshot']
        sql(f"UPDATE application SET status='APPROVED',application_no='FIXTURE-TRAVEL-001',amount=20000.00,submitted_at=UTC_TIMESTAMP(6),applicant_name_snapshot='Fixture',applicant_department_name_snapshot='Fixture Department' WHERE id={source}")
        payload = {'description': 'Related expense', 'relatedApplicationId': source,
            'items': [{'clientKey': 'related1', 'type': 'MEALS', 'expenseDate': '2026-11-10', 'purpose': 'Meal', 'amount': '1.00', 'noReceipt': True, 'noReceiptReason': 'Lost'}]}
        aid = create('REIMBURSEMENT', payload)
        validate(aid, 503)  # Student-owned schema intentionally absent: safe failure, no draft fallback.
        sql('CREATE TABLE flow_instance(application_id BIGINT UNSIGNED PRIMARY KEY,form_snapshot JSON NOT NULL)')
        sql("GRANT SELECT ON approval.flow_instance TO 'approval_app'@'%'")
        encoded = json.dumps(snapshot, ensure_ascii=False).encode().hex()
        sql(f"INSERT INTO flow_instance(application_id,form_snapshot) VALUES({source},CONVERT(0x{encoded} USING utf8mb4))")
        related = validate(aid).data['formSnapshot']['relatedApplicationSnapshot']
        require(related['applicationNo'] == 'FIXTURE-TRAVEL-001' and related['budget'] == '20000.00' and related['origin'] == travel['origin'], 'related snapshot summary incomplete')
        require(set(related) == {'applicationId', 'applicationNo', 'type', 'title', 'origin', 'destination', 'startAt', 'endAt', 'budget'}, 'related snapshot leaked non-summary source fields')
        purchase_source = create('PURCHASE', purchase)
        purchase_snapshot = validate(purchase_source).data['formSnapshot']
        sql(f"UPDATE application SET status='APPROVED',application_no='FIXTURE-PURCHASE-001',amount=11.05,submitted_at=UTC_TIMESTAMP(6),applicant_name_snapshot='Fixture',applicant_department_name_snapshot='Fixture Department' WHERE id={purchase_source}")
        purchase_encoded = json.dumps(purchase_snapshot, ensure_ascii=False).encode().hex()
        sql(f"INSERT INTO flow_instance(application_id,form_snapshot) VALUES({purchase_source},CONVERT(0x{purchase_encoded} USING utf8mb4))")
        purchase_reimbursement = json.loads(json.dumps(payload))
        purchase_reimbursement['relatedApplicationId'] = purchase_source
        purchase_summary = validate(create('REIMBURSEMENT', purchase_reimbursement)).data['formSnapshot']['relatedApplicationSnapshot']
        require(purchase_summary['project'] == 'Project A' and purchase_summary['expectedTotal'] == '11.05', 'purchase association summary incomplete')
        require(set(purchase_summary) == {'applicationId', 'applicationNo', 'type', 'title', 'project', 'expectedTotal'}, 'purchase summary leaked item or quote bodies')
        second = create('REIMBURSEMENT', payload)
        require(validate(second).data['amount'] == '1.00', 'repeat association wrongly budget deducted')
        require(h.scalar(f'SELECT CAST(amount AS CHAR) FROM application WHERE id={source}') == '20000.00', 'related original amount mutated')
        payload['relatedApplicationId'] = create('TRAVEL', travel)
        save(aid, payload); validate(aid, 400)
        sql(f"UPDATE application SET applicant_id={state['other_id']} WHERE id={source}")
        payload['relatedApplicationId'] = source
        save(aid, payload); validate(aid, 400)
        grants = h.mysql("SHOW GRANTS FOR 'approval_app'@'%'")
        require(grants.returncode == 0 and 'SELECT' in grants.stdout and 'flow_instance' in grants.stdout, 'runtime read-only fixture grant unavailable')
        return 'missing student snapshot schema503; explicit isolated read-only flow_instance fixture only; own approved summary, repeated association no decrement, other owner/draft denied; no flow generation'
    h.check('approved related application snapshot contract', related_snapshots)

    def parallel_binding():
        sql('DELETE FROM rate_limit_bucket')
        aid = create()
        fid = expect(upload(app, state['owner_token'], PNG + b'parallel'), 201).data['fileId']
        ver, request_key = version(aid), key()
        replies = parallel_calls(4, lambda _: bind(aid, fid, request_key=request_key, expected_version=ver))
        require(all(r.status == 200 for r in replies), 'parallel replay failed')
        require(all(r.data == replies[0].data for r in replies), 'parallel replay response differs')
        require(h.scalar(f'SELECT COUNT(*) FROM file_binding WHERE application_id={aid}') == '1', 'duplicate parallel bindings')
        require(int(version(aid)) == int(ver) + 1, 'parallel binding version advanced twice')
        return 'four real concurrent bind retries: one DB binding and one version increment, identical success replay'
    h.check('concurrent binding replay', parallel_binding)

    def unbind_delete():
        aid, bid = state['binding_application'], state['binding_id']
        fid = state['file']['fileId']
        request_key, ver = key(), version(aid)
        path = f'/applications/{aid}/bindings/{bid}'
        first = expect(req('DELETE', path, {'expectedVersion': ver}, request_key=request_key), 200)
        replay = expect(req('DELETE', path, {'expectedVersion': ver}, request_key=request_key), 200)
        require(first.data == replay.data, 'unbind replay differs')
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if h.scalar(f"SELECT status FROM file_object WHERE id={fid}") == 'DELETED':
                break
            time.sleep(.3)
        require(h.scalar(f"SELECT status FROM file_object WHERE id={fid}") == 'DELETED', 'persistent cleanup did not delete unbound object')
        require(h.scalar(f'SELECT COUNT(*) FROM file_binding WHERE file_id={fid}') == '0', 'unbind retained binding')
        return 'unbind atomic version/replay; persistent worker moved unreferenced object through DELETING to DELETED'
    h.check('persistent cleanup after unbind', unbind_delete)

    def shared_and_damaged():
        sql('DELETE FROM rate_limit_bucket')
        a, b = create(), create()
        fid = expect(upload(app, state['owner_token'], PNG + b'shared bytes'), 201).data['fileId']
        first = expect(bind(a, fid), 200).data['bindingId']
        second = expect(bind(b, fid), 200).data['bindingId']
        expect(req('DELETE', f'/applications/{a}/bindings/{first}', {'expectedVersion': version(a)}, request_key=key()), 200)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if h.scalar(f"SELECT COUNT(*) FROM file_cleanup WHERE file_id={fid} AND status='DONE'") != '0':
                break
            time.sleep(.2)
        require(h.scalar(f"SELECT status FROM file_object WHERE id={fid}") == 'READY', 'shared referenced object deleted')
        expect(binary_request(app, 'GET', f'/files/{fid}/preview?applicationId={b}', state['owner_token']), 200)
        storage_key = h.scalar(f'SELECT storage_key FROM file_object WHERE id={fid}')
        volume = Path(os.environ['APP_UPLOAD_DIR']).resolve()
        physical = (volume / storage_key).resolve()
        require(physical.parent == volume and physical.name == storage_key and physical.is_file(), 'fixture path outside private volume or unexpected key')
        physical.unlink()  # Verified exact isolated-volume object, simulate disk damage.
        preview = binary_request(app, 'GET', f'/files/{fid}/preview?applicationId={b}', state['owner_token'])
        require(preview.status in (404, 503), 'missing bytes served')
        require(h.scalar(f'SELECT integrity_status FROM file_object WHERE id={fid}') == 'DAMAGED', 'missing file not marked damaged')
        require(h.scalar(f'SELECT COUNT(*) FROM file_binding WHERE id={second}') == '1', 'historical damaged binding was deleted')
        require(bind(create(), fid).status in (400, 409, 503), 'damaged object newly bound')
        expect(req('DELETE', f'/applications/{b}/bindings/{second}', {'expectedVersion': version(b)}, request_key=key()), 200)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and h.scalar(f"SELECT status FROM file_object WHERE id={fid}") != 'DELETED':
            time.sleep(.2)
        require(h.scalar(f"SELECT status FROM file_object WHERE id={fid}") == 'DELETED', 'already-missing disk bytes did not clean successfully')
        return 'shared reference protects bytes after one unbind; missing bytes marked DAMAGED and history retained; new binding denied; last unbind cleans missing bytes successfully'
    h.check('shared reference cleanup and damaged object protection', shared_and_damaged)

    def slow_network_deadline():
        sql('DELETE FROM rate_limit_bucket')
        boundary = 'isolated' + secrets.token_hex(12)
        prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="slow.png"\r\nContent-Type: image/png\r\n\r\n').encode() + PNG[:8]
        conn = http.client.HTTPConnection('127.0.0.1', app.port, timeout=135)
        started = time.monotonic()
        try:
            conn.putrequest('POST', '/api/v1/files')
            conn.putheader('Authorization', 'Bearer ' + state['owner_token'])
            conn.putheader('Content-Type', 'multipart/form-data; boundary=' + boundary)
            conn.putheader('Content-Length', str(1024 * 1024))
            conn.endheaders()
            conn.send(prefix)  # Keep connection open without finishing the promised request body.
            response = conn.getresponse()
            payload = json.loads(response.read())
            elapsed = time.monotonic() - started
            if response.status != 408 or payload.get('code') != 'UPLOAD_TIMEOUT':
                app.log.seek(0)
                diagnostics = sorted(set(re.findall(r'[A-Za-z0-9_.]+Exception', app.log.read().decode('utf-8', 'replace'))))
                print(f'Upload failure exception types only: {diagnostics}', file=sys.stderr)
            require(response.status == 408 and payload.get('code') == 'UPLOAD_TIMEOUT', f'stalled network did not return structured408: status={response.status}, elapsed={elapsed:.2f}s, payload={payload!r}')
            require(115 <= elapsed <= 130, f'network deadline outside2min tolerance: {elapsed:.2f}s')
            require(h.scalar("SELECT COUNT(*) FROM file_object WHERE original_name='slow.png' AND status='READY'") == '0', 'timed-out network published READY')
            app.requests += 1
            return f'real unfinished TCP multipart request received408 UPLOAD_TIMEOUT after{elapsed:.2f}s; no READY row; no client timeout shortening'
        finally:
            conn.close()
    h.check('real stalled network two minute deadline', slow_network_deadline)

    def interruption_recovery():
        app.stop()
        volume = Path(os.environ['APP_UPLOAD_DIR']).resolve()
        keys = [secrets.token_hex(16) for _ in range(6)]
        partial, renamed, deleting, aged, orphan, live = keys
        for object_key, suffix in ((partial, '.part'), (renamed, ''), (aged, ''), (orphan, ''), (live, '.part')):
            target = (volume / (object_key + suffix)).resolve()
            require(target.parent == volume, 'recovery fixture outside isolated volume')
            target.write_bytes(PNG)
        old = time.time() - 25 * 3600
        os.utime(volume / orphan, (old, old))
        ids = []
        for object_key, status in ((partial, 'UPLOADING'), (renamed, 'UPLOADING'), (deleting, 'DELETING'), (aged, 'READY'), (live, 'UPLOADING')):
            lease = "DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE)" if object_key == live else "DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)" if status == 'UPLOADING' else 'NULL'
            digest = hashlib.sha256(PNG).hexdigest()
            sql(f"INSERT INTO file_object(owner_id,storage_key,original_name,media_type,byte_size,sha256,status,upload_lease_until,created_at,updated_at) VALUES({state['owner_id']},'{object_key}','fixture.png','image/png',{len(PNG)},UNHEX('{digest}'),'{status}',{lease},DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 25 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 25 HOUR))")
            ids.append(h.scalar(f"SELECT CAST(id AS CHAR) FROM file_object WHERE storage_key='{object_key}'"))
        sql(f"INSERT INTO file_cleanup(file_id,storage_key,status,created_at,updated_at) VALUES({ids[2]},'{deleting}','PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
        app.start()
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            finished = h.scalar(f"SELECT COUNT(*) FROM file_object WHERE id IN ({','.join(ids[:4])}) AND status='DELETED'")
            if finished == '4' and not (volume / orphan).exists():
                break
            time.sleep(.2)
        require(finished == '4', 'restart recovery did not finish expired transfer/renamed/removed/24h-unbound objects')
        require(not (volume / partial).exists() and not (volume / (partial + '.part')).exists() and not (volume / renamed).exists() and not (volume / aged).exists(), 'recovery retained stale physical bytes')
        require(not (volume / orphan).exists(), 'daily orphan reconciliation did not remove old unknown object')
        require(h.scalar(f'SELECT status FROM file_object WHERE id={ids[4]}') == 'UPLOADING' and (volume / (live + '.part')).exists(), 'cleanup stole active upload lease')
        return 'restart with upload-partial, renamed-before-READY, already-deleted bytes and 24h-unbound fixtures recovered; old disk orphan reclaimed; live15min upload preserved; fixtures are explicit, not real process-kill claims'
    h.check('interruption-state restart recovery and lease protection', interruption_recovery)

    def rate_limit():
        sql('DELETE FROM rate_limit_bucket')
        for _ in range(30):
            reply = upload(app, state['other_token'], b'invalid', 'x.exe')
            require(reply.status in (400, 415), 'upload attempt not counted as expected')
        reply = upload(app, state['other_token'])
        expect(reply, 429)
        require(reply.header('Retry-After') is not None, 'rate limit lacks retry-after')
        return '30 attempts/account/min including rejected bodies persist in MySQL;31st429+Retry-After'
    h.check('persistent upload rate limit', rate_limit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keep', action='store_true')
    parser.add_argument('--json-out', type=Path, default=BACKEND / 'evidence/task060708-mysql.json')
    args = parser.parse_args()
    h = MysqlHarness(find_java(), build=False, keep=args.keep, migration_target=None)
    app = Application(h)
    result = 0
    with tempfile.TemporaryDirectory(prefix='approval-files-it-') as volume:
        old = {name: os.environ.get(name) for name in ('APP_UPLOAD_DIR', 'APP_FILES_CLEANUP_DELAY_MS', 'APP_FILES_RECONCILE_DELAY_MS')}
        os.environ['APP_UPLOAD_DIR'] = volume
        os.environ['APP_FILES_CLEANUP_DELAY_MS'] = '200'
        os.environ['APP_FILES_RECONCILE_DELAY_MS'] = '300'
        try:
            require(docker(['version']).returncode == 0, 'Docker is required')
            verify(h, app)
        except Exception as error:
            print(f'TASK06/07/08 verification FAIL: {error}', file=sys.stderr)
            result = 1
        finally:
            if len(h.checks) != 14 or any(x['status'] != 'PASS' for x in h.checks):
                result = 1
            app.close()
            artifact = {'task': 'TASK-06/07/08', 'generated_at': datetime.now(timezone.utc).isoformat(),
                'verdict': 'PASS' if result == 0 else 'FAIL', 'checks': h.checks,
                'passed_check_count': sum(x['status'] == 'PASS' for x in h.checks),
                'jar_sha256': app.jar_sha256,
                'migration_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(MIGRATION_DIR.glob('V*.sql'))},
                'mysql_image': IMAGE, 'http_requests': app.requests, 'success_envelopes_checked': app.envelopes,
                'application_starts': app.starts, 'jvm_timezone': 'Asia/Shanghai',
                'runtime_database_account': 'approval_app', 'credentials_recorded': False,
                'scope': 'fixed form validation and private files only; no workflow/formal-row implementation or full T acceptance'}
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            h.stop()
            for name, value in old.items():
                if value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = value
    return result

if __name__ == '__main__':
    raise SystemExit(main())
