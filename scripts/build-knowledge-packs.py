"""Convert an official Chinese Wikivoyage XML snapshot into directly importable JSON.
No model calls, no embeddings, no scraping of commercial travel sites.
Install scripts/knowledge-data-requirements.txt; pass --dump path.xml.bz2.
"""
import argparse
import bz2
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import mwparserfromhell
from opencc import OpenCC

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'travel-h5-java/public/knowledge-packs'
LICENSE = 'CC BY-SA 4.0'
LICENSE_URL = 'https://creativecommons.org/licenses/by-sa/4.0/'
cc = OpenCC('t2s')

def clean(text):
    code = mwparserfromhell.parse(text)
    for link in list(code.filter_wikilinks(recursive=False)):
        if re.match(r'^(?:file|image|category|文件|檔案|图像|圖像|分类|分類):', str(link.title), re.I):
            code.replace(link, '')
    for template in list(code.filter_templates(recursive=False)):
        name = str(template.name).strip().lower()
        if name in {'pagebanner', 'geo', 'ispartof', 'quickbar', 'voy', 'regionlist', 'mapframe', 'mapmask'}:
            code.replace(template, '')
            continue
        fields = []
        for key, label in [('name', '名称'), ('alt', '别名'), ('address', '地址'), ('directions', '到达'),
                           ('content', '介绍'), ('description', '介绍'), ('hours', '原页开放信息'), ('price', '原页价格信息')]:
            if template.has(key):
                value = mwparserfromhell.parse(str(template.get(key).value)).strip_code().strip()
                if value:
                    fields.append(label + '：' + value)
        if not fields and name in {'warning', 'caution', 'note', '警告', '提示', 'lang', 'nowrap', 'convert'}:
            fields = [str(p.value).strip() for p in template.params if str(p.name).strip().isdigit()]
        code.replace(template, '\n' + '；'.join(fields) + '\n' if fields else '')
    value = cc.convert(code.strip_code(normalize=True, collapse=True))
    value = re.sub(r'^\s*__\w+__\s*$', '', value, flags=re.M)
    value = re.sub(r'\n[ \t]*\n(?:[ \t]*\n)+', '\n\n', value)
    return value.strip()

def save_pack(name, title, docs, metadata):
    pack = dict(format='travel-knowledge/v1', title=title, **metadata, documents=docs)
    raw = (json.dumps(pack, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    (OUT / name).write_bytes(raw)
    return dict(title=title, url='/knowledge-packs/' + name, documents=len(docs),
                cities=len({d['city'] for d in docs}), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())

def build(dump):
    catalog = json.loads((ROOT/'travel-java-server/src/main/resources/knowledge/destinations.json').read_text(encoding='utf-8'))
    aliases = {}
    for city in catalog:
        for name in [city['fullName'], city['name'], city['name'].removesuffix('市')]:
            aliases.setdefault(name, city)
    found, redirects = {}, 0
    ns = '{http://www.mediawiki.org/xml/export-0.11/}'
    with bz2.open(dump, 'rb') as source:
        for _, page in ET.iterparse(source, events=('end',)):
            if page.tag.rsplit('}',1)[-1] != 'page':
                continue
            # Detect XML namespace rather than depending on one dump version.
            ns = page.tag[:-4]
            title = cc.convert(page.findtext(ns+'title', ''))
            city = aliases.get(title)
            if not city or page.findtext(ns+'ns') != '0' or page.find(ns+'redirect') is not None:
                page.clear(); continue
            revision = page.find(ns+'revision')
            revision_id = revision.findtext(ns+'id')
            timestamp = revision.findtext(ns+'timestamp', '')
            content = clean(revision.findtext(ns+'text', ''))
            if len(content) < 100:
                page.clear(); continue
            if len(content) > 9000:
                end = content.rfind('\n', 7500, 9000)
                content = content[:end if end > 7500 else 9000].rstrip() + '\n\n（原页节选，完整指南见来源链接。）'
            url = 'https://zh.wikivoyage.org/w/index.php?oldid=' + revision_id
            footer = f'\n\n---\n来源：中文维基导游《{title}》，原页修订时间 {timestamp}（不是发布日期）。\n原页固定版本与作者历史：{url} 。作者为该原页的维基导游贡献者，作者列表见原页历史。\n许可：{LICENSE}，{LICENSE_URL} 。本项目作了维基标记清洗、简繁转换和长度节选；改编文本按相同许可提供。此为离线快照，价格、开放时间及预约信息不代表当前状态。'
            doc = dict(title=f'{city["name"]}旅行指南 · 维基导游节选', city=city['fullName'], sourceUrl=url,
                       publishedOn=None, validFrom=None, validTo=None, content=content+footer)
            if city['code'] not in found or len(found[city['code']][1]['content']) < len(doc['content']):
                found[city['code']] = (city,doc)
            page.clear()
    ordered = [found[k] for k in sorted(found)]
    metadata = dict(source='Chinese Wikivoyage official XML dump', sourceUrl='https://dumps.wikimedia.org/zhwikivoyage/',
                    license=LICENSE, licenseUrl=LICENSE_URL, dumpSha256=hashlib.sha256(dump.read_bytes()).hexdigest(),
                    notice='仅包含首页城市目录中在此快照有对应正文的目的地；一城一份节选，不保证覆盖全部城市或实时信息。保留原页修订时间，不将修订时间当作发布日期。')
    OUT.mkdir(parents=True, exist_ok=True)
    entries = [save_pack('wikivoyage-china.json', '中文维基导游 · 全国目的地基础资料包', [d for c,d in ordered], metadata)]
    for province in sorted({c['provinceCode'] for c,d in ordered}):
        rows = [(c,d) for c,d in ordered if c['provinceCode'] == province]
        entries.append(save_pack('wikivoyage-'+province+'.json', rows[0][0]['provinceName']+' · 维基导游资料包', [d for c,d in rows], metadata))
    manifest = dict(format='travel-knowledge-manifest/v1', catalogDestinations=len(catalog), coveredDestinations=len(ordered),
                    missingDestinations=[c['fullName'] for c in catalog if c['code'] not in found], **metadata, packs=entries)
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(catalog=len(catalog),covered=len(ordered),packs=len(entries),bytes=entries[0]['bytes'],sha256=metadata['dumpSha256']),ensure_ascii=False))

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--dump', type=Path, required=True)
    build(parser.parse_args().dump)
