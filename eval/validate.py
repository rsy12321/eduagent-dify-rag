#!/usr/bin/env python3
"""Validate the local mechanics corpus and evaluation files, using only the stdlib.

Run from any directory: python3 /path/to/eduagent/eval/validate.py
This script reads files only and never makes network requests.
Semantic correctness and whether an answer is supported by a section need review.
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
KB_DIR = ROOT / "kb" / "physics-mechanics"
EVAL_DIR = ROOT / "eval"
LECTURES = (
    "01-mechanical-motion.md",
    "02-force.md",
    "03-motion-and-force.md",
    "04-pressure.md",
    "05-liquid-gas-pressure.md",
    "06-buoyancy.md",
    "07-work-energy.md",
    "08-simple-machines.md",
)
HEADINGS = (
    "## 1 核心概念",
    "## 2 公式与单位",
    "## 3 常见误区",
    "## 4 生活中的例子",
    "## 5 小结",
)
FIELDS = (
    "id", "type", "question", "expected_route",
    "reference_answer", "source", "judging_criteria",
)
TYPE_COUNTS = {"qa": 12, "generate": 4, "out_of_scope": 4}
ROUTES = {"qa": "答疑", "generate": "出题", "out_of_scope": "答疑"}


def markdown_cell(value):
    """Encode a JSON field as one Markdown table cell without losing content."""
    if value is None:
        return "null"
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>")


def expected_markdown(questions):
    lines = [
        "# 初中物理·力学评测集（20 题）",
        "",
        "本表由 questions.json 逐字段生成；source 为 null 表示知识库外问题。",
        "",
        "| " + " | ".join(FIELDS) + " |",
        "| " + " | ".join("---" for _ in FIELDS) + " |",
    ]
    for question in questions:
        lines.append("| " + " | ".join(markdown_cell(question[key]) for key in FIELDS) + " |")
    return "\n".join(lines) + "\n"


def main():
    errors = []
    lecture_texts = {}
    word_counts = []
    for filename in LECTURES:
        path = KB_DIR / filename
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append(f"无法读取讲义 {filename}: {exc}")
            continue
        lecture_texts[filename] = content
        headings = tuple(line for line in content.splitlines() if line.startswith("## "))
        if headings != HEADINGS:
            errors.append(f"{filename}: 五个二级标题的文字或顺序不符合要求")
        body = "\n".join(line for line in content.splitlines() if not line.startswith("#"))
        han_count = len(re.findall(r"[\u4e00-\u9fff]", body))
        nonspace_count = len(re.sub(r"\s", "", body))
        word_counts.append((filename, han_count, nonspace_count))
        if not 1000 <= han_count <= 2000:
            errors.append(f"{filename}: 正文汉字数 {han_count} 不在 1000–2000 范围内")

    actual_lectures = {path.name for path in KB_DIR.glob("[0-9]*.md")}
    if actual_lectures != set(LECTURES):
        errors.append("编号讲义文件集合与要求的八篇不一致")
    try:
        readme = (KB_DIR / "README.md").read_text(encoding="utf-8")
        if "内容为原创教学讲义，不含电学/光学/热学" not in readme:
            errors.append("README.md 缺少要求的范围声明")
        if any(filename not in readme for filename in LECTURES):
            errors.append("README.md 的讲义文件清单不完整")
    except (OSError, UnicodeError) as exc:
        errors.append(f"无法读取 README.md: {exc}")

    try:
        questions = json.loads((EVAL_DIR / "questions.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        questions = None
        errors.append(f"无法读取或解析 questions.json: {exc}")
    if not isinstance(questions, list):
        errors.append("questions.json 顶层必须是数组")
        questions = []
    if len(questions) != 20:
        errors.append(f"题目数量应为 20，实际为 {len(questions)}")

    ids = []
    counts = Counter()
    qa_coverage = set()
    sources_checked = 0
    schema_valid = True
    for index, question in enumerate(questions, start=1):
        label = f"第 {index} 题"
        if not isinstance(question, dict):
            errors.append(f"{label}: 必须为对象")
            schema_valid = False
            continue
        if set(question) != set(FIELDS):
            missing = sorted(set(FIELDS) - set(question))
            extra = sorted(set(question) - set(FIELDS))
            errors.append(f"{label}: 字段不符，缺少 {missing}，多出 {extra}")
            schema_valid = False
            continue
        if any(not isinstance(question[key], str) or not question[key].strip()
               for key in FIELDS if key != "source"):
            errors.append(f"{label}: 除 source 外所有字段必须为非空字符串")
            schema_valid = False
            continue
        label = question["id"]
        ids.append(label)
        if label != f"Q{index:02d}":
            errors.append(f"{label}: 编号应为 Q{index:02d}")
        kind = question["type"]
        counts[kind] += 1
        if kind not in ROUTES:
            errors.append(f"{label}: 未知 type {kind}")
        elif question["expected_route"] != ROUTES[kind]:
            errors.append(f"{label}: expected_route 与 type 不匹配")

        source = question["source"]
        if kind == "out_of_scope" and source is not None:
            errors.append(f"{label}: out_of_scope 的 source 必须为 null")
        if source is None:
            if kind != "out_of_scope":
                errors.append(f"{label}: qa 和 generate 必须提供 source")
            continue
        if not isinstance(source, str):
            errors.append(f"{label}: source 必须为字符串或 null")
            schema_valid = False
            continue
        match = re.fullmatch(r"([^/#]+\.md)#([1-5] [^\n#]+)", source)
        if not match:
            errors.append(f"{label}: source 格式不符")
            continue
        filename, section = match.groups()
        if filename not in lecture_texts:
            errors.append(f"{label}: source 对应的讲义不存在或不可读: {filename}")
            continue
        if "## " + section not in lecture_texts[filename].splitlines():
            errors.append(f"{label}: source 对应的小节标题不存在: {section}")
            continue
        sources_checked += 1
        if kind == "qa":
            qa_coverage.add(filename)

    if len(ids) != len(set(ids)):
        errors.append("id 存在重复")
    if counts != Counter(TYPE_COUNTS):
        errors.append(f"type 数量应为 {TYPE_COUNTS}，实际为 {dict(counts)}")
    if qa_coverage != set(LECTURES):
        errors.append(f"qa 未覆盖全部讲义，缺少 {sorted(set(LECTURES) - qa_coverage)}")

    if schema_valid:
        try:
            actual_md = (EVAL_DIR / "questions.md").read_text(encoding="utf-8")
            if actual_md != expected_markdown(questions):
                errors.append("questions.md 与 questions.json 的逐字段表格内容不一致")
        except (OSError, UnicodeError) as exc:
            errors.append(f"无法读取 questions.md: {exc}")

    if errors:
        print(f"FAIL: {len(errors)} 项校验失败")
        for error in errors:
            print(f"- {error}")
        return 1

    print("PASS: 8 篇讲义 + README 存在，固定小节标题及字数检查通过")
    for filename, han_count, nonspace_count in word_counts:
        print(f"  {filename}: 正文汉字 {han_count}，正文非空白字符 {nonspace_count}")
    print("PASS: 恰好 20 题，id 唯一且为 Q01–Q20，必填字段齐全且格式正确")
    print("PASS: type 数量 qa=12, generate=4, out_of_scope=4；路由正确")
    print(f"PASS: {sources_checked} 个非空 source 的文件和小节标题均存在；4 个知识库外 source 为 null")
    print("PASS: qa 覆盖全部 8 篇讲义")
    print("PASS: questions.json 与 questions.md 逐字段一致")
    print("RESULT: 全部自动校验通过（科学准确性与答案出处依据需人工审阅）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
