import re, pathlib

base = pathlib.Path(r"C:\Users\刘超\Documents\SmartPlanner")

# Fix AgentChatService.java
f = base / "user-service/src/main/java/com/chao/user/service/AgentChatService.java"
java = f.read_text(encoding="utf-8")

# Strip markdown in sanitize
java = java.replace(
    's = s.replace("```", "\u0027\u0027\u0027");',
    's = s.replace("###", "");\n        s = s.replace("**", "");\n        s = s.replace("`", "");\n        s = s.replace("*", "");\n        s = s.replace("#", "");\n        s = s.replace("```", "");\n        s = s.replace("~~", "");'
)

# Replace system prompt
new_prompt = (
    '    private static final String SYSTEM_PROMPT = """\n'
    '            \u4f60\u662f\u53ea\u8bfb\u52a9\u624b\u3002\u7981\u6b62\u4f7f\u7528\u4efb\u4f55markdown\u7b26\u53f7\u3002\n'
    '            \u53ea\u7528\u7eaf\u6587\u672c\uff1a\u6362\u884c\u5206\u6bb5\uff0c\u5b50\u9879\u5f00\u5934\u7a7a\u4e24\u683c\u7f29\u8fdb\u3002\n'
    '\n'
    '            \u89c4\u5219\uff1a\n'
    '            1. \u4eca\u5929\u6709\u4ec0\u4e48/\u505a\u4ec0\u4e48/\u65e5\u7a0b \u2192 \u8c03listTodaySchedules\u3002\u7981\u6b62\u7f16\u9020\u65e5\u7a0b\u3002\n'
    '            2. \u7edd\u4e0d\u8bf4\u201c\u5efa\u8bae\u7684\u65e5\u7a0b\u201d\u201c\u53ef\u4ee5\u5b89\u6392\u201d\u201c\u5047\u8bbe\u4f60\u6709\u201d\u201c\u4e0a\u5348X\u70b9\u201d\u3002\n'
    '            3. \u65e0\u6392\u7a0b\u65f6\u8bf4\u201c\u4eca\u5929\u6682\u65e0\u6392\u7a0b\uff0c\u53bb\u65e5\u7a0b\u9875\u9762\u521b\u5efa\u5427\u201d \u8df3\u8f6c: /schedule\n'
    '            4. \u672b\u5c3e\u52a0\u8df3\u8f6c\uff1a\u8df3\u8f6c: /path\n'
    '            5. \u4e0d\u91cd\u590d\u5185\u5bb9\u3002\n'
    '\n'
    '            \u8df3\u8f6c\uff1a/\u4eea\u8868\u76d8 /plan\u5b66\u4e60\u8ba1\u5212 /goals\u76ee\u6807 /journals\u968f\u7b14 /schedule\u65e5\u7a0b /resources\u8d44\u6e90 /punch\u6253\u5361 /profile\u753b\u50cf\n'
    '            """;'
)

java = re.sub(r'private static final String SYSTEM_PROMPT = """[\s\S]*?""";', new_prompt, java)
f.write_text(java, encoding="utf-8")
print("Java fixed")

# Fix assistant.js dedup
f2 = base / "web-front/src/stores/assistant.js"
js = f2.read_text(encoding="utf-8")
js = js.replace(
    "aiMsg.text = buf.trim() || '\u6211\u6682\u65f6\u6ca1\u60f3\u597d\uff0c\u53ef\u4ee5\u6362\u4e2a\u95ee\u6cd5\u5417\uff1f';",
    "            const seen = new Set();\n            const paras = buf.split(/\\n{2,}/);\n            const out = [];\n            for (const p of paras) {\n              const key = p.trim();\n              if (!key || seen.has(key)) continue;\n              seen.add(key);\n              out.push(p);\n            }\n            buf = out.join('\\n\\n');\n            aiMsg.text = buf.trim() || '\u6211\u6682\u65f6\u6ca1\u60f3\u597d\uff0c\u53ef\u4ee5\u6362\u4e2a\u95ee\u6cd5\u5417\uff1f';"
)
f2.write_text(js, encoding="utf-8")
print("JS fixed")
