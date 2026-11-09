from pathlib import Path
from docx import Document
from zipfile import ZipFile
from lxml import etree
root = Path(__file__).resolve().parents[3]
out = Path(__file__).resolve().parent / 'materials'
out.mkdir(exist_ok=True)
for path in sorted((root / '课件').glob('*.docx')):
    if path.name.startswith('~$'):
        continue
    doc = Document(path)
    lines = []
    for block in doc.iter_inner_content():
        if hasattr(block, 'rows'):
            lines.extend(' | '.join(cell.text for cell in row.cells) for row in block.rows)
        else:
            lines.append(block.text)
    # Course transcripts use text boxes / unsupported runs, omitted by paragraph.text.
    with ZipFile(path) as archive:
        xml = etree.fromstring(archive.read('word/document.xml'))
    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    lines = xml.xpath('//w:t/text()', namespaces=ns)
    target = out / (path.stem + '.txt')
    target.write_text('\n'.join(lines), encoding='utf-8')
    print(f'{path.name}: {len(doc.paragraphs)} paragraphs, {len(doc.tables)} tables -> {target.name}')
