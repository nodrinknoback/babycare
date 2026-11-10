"""从统一多语言资源生成公开政策，避免应用与 GitHub Pages 各自维护一份正文。"""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/language/output.json'
DESTINATION = ROOT / 'docs/privacy'
LANGUAGES = {'en': ('English', 'index.html'), 'zh-Hans': ('简体中文', 'zh-Hans.html'),
             'zh-Hant': ('繁體中文', 'zh-Hant.html'), 'ja': ('日本語', 'ja.html'), 'ko': ('한국어', 'ko.html')}


def generate():
    data = json.loads(SOURCE.read_text())['data']['common']
    entries = {item['codeKey']: item['language'] for item in data}
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for language, (label, filename) in LANGUAGES.items():
        title = entries['privacy_policy_title'][language]
        body = entries['privacy_policy_body'][language].replace('\\n', '\n')
        paragraphs = ''.join('<p>' + html.escape(part).replace('\n', '<br>') + '</p>' for part in body.split('\n\n'))
        paragraphs = paragraphs.replace('nodrinknoback@gmail.com', '<a href="mailto:nodrinknoback@gmail.com">nodrinknoback@gmail.com</a>')
        github_policy = 'https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement'
        paragraphs = paragraphs.replace(github_policy, f'<a href="{github_policy}">GitHub Privacy Statement</a>')
        navigation = ' · '.join(f'<a lang="{code}" href="{path}"' + (' aria-current="page"' if code == language else '') + f'>{name}</a>'
                                for code, (name, path) in LANGUAGES.items())
        page = f'''<!doctype html>
<html lang="{language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>BabyCare · {html.escape(title)}</title>
<style>body{{font:16px/1.75 system-ui,sans-serif;max-width:760px;margin:0 auto;padding:32px 22px 64px;background:#fffaf5;color:#292b2d}}h1{{font-size:28px;line-height:1.3}}nav{{margin:24px 0}}a{{color:#176382;overflow-wrap:anywhere}}p{{margin:24px 0}}a:focus-visible{{outline:2px solid;outline-offset:4px}}@media(prefers-color-scheme:dark){{body{{background:#202323;color:#f3eee8}}a{{color:#9ad4ec}}}}</style></head>
<body><header><h1>BabyCare · {html.escape(title)}</h1><nav aria-label="Language">{navigation}</nav></header><main>{paragraphs}</main></body></html>
'''
        (DESTINATION / filename).write_text(page, encoding='utf-8')
    (DESTINATION / '.nojekyll').touch()
    print(f'已生成 {len(LANGUAGES)} 种语言的政策页面：{DESTINATION}')


if __name__ == '__main__':
    generate()
