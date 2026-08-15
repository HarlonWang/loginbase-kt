#!/usr/bin/env python3
"""编译器裁判式未用 import 清理。

阶段 1（本脚本 remove）：宽松候选——把「代码体里没有字面引用」的 import 全删。
  文本解析允许过删（假阳性自愈）：字符串/注释整体剥离，剥离出错顶多多删。
  仅两类不删：KDoc [引用] 里出现的名字（编译器看不见它们）、操作符/委托白名单。
阶段 2（本脚本 restore <编译日志>）：解析 unresolved reference，把误删的恢复。
循环 remove→compile→restore 至编译绿，剩下的删除即编译器背书的真未用。
"""
import os, re, sys, json

OPERATOR_IMPORTS = {'getValue', 'setValue', 'provideDelegate', 'invoke', 'contains',
                    'rangeTo', 'compareTo', 'iterator', 'plus', 'minus', 'times', 'div',
                    'get', 'set', 'unaryMinus', 'unaryPlus', 'inc', 'dec', 'not',
                    'component1', 'component2', 'component3'}

STATE = 'unused_imports_state.json'

def code_body_and_kdoc_refs(text):
    kdoc_refs = []
    for m in re.finditer(r'/\*.*?\*/', text, flags=re.S):
        kdoc_refs += re.findall(r'\[([\w.]+)\]', m.group(0))
    body = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    body = re.sub(r'//.*$', ' ', body, flags=re.M)
    # 字符串剥离（保留 ${} 内容）：宽松即可，剥错顶多过删、由编译器纠回
    templates = re.findall(r'\$\{([^{}]*)\}', body)
    body = re.sub(r'""".*?"""', ' ', body, flags=re.S)
    body = re.sub(r'"(?:\\.|[^"\\\n])*"', ' ', body)
    body = re.sub(r'^import .*$', ' ', body, flags=re.M)
    return body + ' ' + ' '.join(templates), set(kdoc_refs)

def cmd_remove(base, roots):
    removed = {}  # path -> {name: import_line}
    for root in roots:
        for dp, _, fns in os.walk(os.path.join(base, root)):
            if '/build/' in dp: continue
            for fn in fns:
                if not fn.endswith('.kt'): continue
                path = os.path.join(dp, fn)
                text = open(path).read()
                body, kdoc = code_body_and_kdoc_refs(text)
                victims = {}
                for m in re.finditer(r'^import ([\w.]+?)(?:\.(`?\w+`?))?(?: as (\w+))?$', text, flags=re.M):
                    if m.group(0).endswith('.*'): continue
                    name = (m.group(3) or m.group(2) or m.group(1).split('.')[-1]).strip('`')
                    if name in OPERATOR_IMPORTS or name in kdoc: continue
                    if not re.search(r'\b' + re.escape(name) + r'\b', body):
                        victims[name] = m.group(0)
                if victims:
                    for imp in victims.values():
                        text = text.replace(imp + '\n', '', 1)
                    open(path, 'w').write(text)
                    removed[path] = victims
    json.dump(removed, open(STATE, 'w'))
    print(f"候选删除 {sum(len(v) for v in removed.values())} 行 / {len(removed)} 文件")

def cmd_restore(logfile):
    removed = json.load(open(STATE))
    # 编译错误行形如 file:.../X.kt:12:34 ... unresolved reference 'Name'  或 : Name
    errors = {}
    for line in open(logfile):
        m = re.search(r'file://(/[^:]+\.kt).*[Uu]nresolved reference.*?[\'":]\s*`?(\w+)`?', line)
        if not m:
            m = re.search(r'^e: (/[^:]+\.kt).*[Uu]nresolved reference.*?[\'":]\s*`?(\w+)`?', line)
        if m:
            errors.setdefault(m.group(1), set()).add(m.group(2))
    fixed = 0
    for path, names in errors.items():
        if path not in removed: continue
        text = open(path).read()
        pkg_end = re.search(r'^package .*$', text, flags=re.M).end()
        for name in names:
            imp = removed[path].pop(name, None)
            if imp:
                text = text[:pkg_end] + '\n' + imp + text[pkg_end:]
                fixed += 1
        open(path, 'w').write(text)
    json.dump(removed, open(STATE, 'w'))
    print(f"按编译错误恢复 {fixed} 行")

def cmd_report():
    removed = json.load(open(STATE))
    n = 0
    for path, victims in sorted(removed.items()):
        for name, imp in victims.items():
            print(f"{path}: {imp}")
            n += 1
    print(f"最终确认未用：{n} 行")

if __name__ == '__main__':
    if sys.argv[1] == 'remove':
        cmd_remove(sys.argv[2], sys.argv[3].split(','))
    elif sys.argv[1] == 'restore':
        cmd_restore(sys.argv[2])
    elif sys.argv[1] == 'report':
        cmd_report()
