"""驗證計畫中的繁體中文召回案例，證據一律使用無 BOM UTF-8。"""
import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:9200')
    parser.add_argument('--output', default='docs/implementation-plans/middleware/evidence/phase-d/d1-analysis.json')
    args = parser.parse_args()

    def request(method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(args.url.rstrip('/') + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    deadline = time.monotonic() + 120
    while True:
        try:
            health = request('GET', '/_cluster/health')
            if health['status'] != 'red':
                break
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError('Elasticsearch 未在 120 秒內就緒')
        time.sleep(1)

    index = 'phase-d-analysis-' + uuid.uuid4().hex
    samples = [
        ('無線藍牙耳機', ['藍牙耳機', '耳機', '藍牙']),
        ('輕量運動鞋', ['運動鞋', '鞋']),
        ('Apple iPhone 15 手機殼', ['iphone', '手機殼', '手機']),
        ('不鏽鋼保溫杯', ['保溫', '不鏽鋼']),
    ]
    result = {'version': request('GET', '/')['version']['number'], 'analysis': [], 'queries': []}
    settings = {'number_of_shards': 1, 'number_of_replicas': 0,
                'analysis': {'normalizer': {'sku_lowercase': {'type': 'custom', 'filter': ['lowercase']}}}}
    mappings = {'dynamic': 'strict', 'properties': {
        'name': {'type': 'text', 'analyzer': 'smartcn', 'fields': {'char': {'type': 'text', 'analyzer': 'standard'}}},
        'sku': {'type': 'keyword', 'normalizer': 'sku_lowercase'}}}
    request('PUT', '/' + index, {'settings': settings, 'mappings': mappings})
    try:
        for i, (name, queries) in enumerate(samples):
            request('PUT', f'/{index}/_doc/{i}', {'name': name, 'sku': f'PHASE-D-{i}'})
            for text in [name] + queries:
                tokens = {}
                for analyzer in ['smartcn', 'standard']:
                    analyzed = request('POST', f'/{index}/_analyze', {'analyzer': analyzer, 'text': text})
                    tokens[analyzer] = [t['token'] for t in analyzed['tokens']]
                result['analysis'].append({'text': text, 'tokens': tokens})
        request('POST', f'/{index}/_refresh')
        cases = [(query, str(i)) for i, (_, queries) in enumerate(samples) for query in queries]
        cases += [('phase-d-0', '0'), ('宇宙火箭', None)]
        for query, expected in cases:
            body = {'query': {'bool': {'should': [
                {'term': {'sku': {'value': query.lower(), 'boost': 5}}},
                {'match': {'name': {'query': query, 'boost': 3}}},
                {'match_phrase': {'name.char': {'query': query, 'boost': 2}}},
            ], 'minimum_should_match': 1}}}
            hits = request('POST', f'/{index}/_search', body)['hits']['hits']
            ids = [hit['_id'] for hit in hits]
            passed = expected in ids if expected is not None else not ids
            result['queries'].append({'keyword': query, 'expectedId': expected, 'ids': ids, 'passed': passed})
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        failures = [q for q in result['queries'] if not q['passed']]
        if failures:
            raise AssertionError(f'分詞召回未達計畫要求：{failures}')
        print(f"Elasticsearch {result['version']}: {len(cases)} 個搜尋案例全部通過")
    finally:
        request('DELETE', '/' + index)


if __name__ == '__main__':
    main()
