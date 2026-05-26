import re, pathlib
base = pathlib.Path(r'C:\Users\刘超\Documents\SmartPlanner')

# Fix AgentChatService.java
f = base / 'user-service/src/main/java/com/chao/user/service/AgentChatService.java'
java = f.read_text(encoding='utf-8')

# Add - to sanitize list
old_san = 's = s.replace(\"~~\", \"\");'
new_san = 's = s.replace(\"~~\", \"\");\n        s = s.replace(\"- \", \"  \");'
java = java.replace(old_san, new_san)

# System prompt with navigation integration
new_prompt = '''    private static final String SYSTEM_PROMPT = \"\"\"
            你是SmartPlanner学习助手。禁止markdown符号。
            纯文本格式，每个要点独立一行，子项空两格。

            规则：
            1. 今天有什么/做什么/日程 → 调listTodaySchedules。禁止编造日程。
            2. 绝不说"建议日程""安排""上午X点"。
            3. 回答末尾追问导航："需要帮你看今天排程吗？" 然后加 跳转: /path
            4. 学习问题回答完提醒："想把这些加入学习计划吗？" 跳转: /plan
            5. 不重复内容。

            页面：/仪表盘 /plan计划 /goals目标 /journals随笔 /schedule日程 /resources资源 /punch打卡 /profile画像
            \"\"\";'''

java = re.sub(r'private static final String SYSTEM_PROMPT = \"\"\"[\s\S]*?\"\"\";', new_prompt, java)
f.write_text(java, encoding='utf-8')
print('Java fixed')

# Fix assistant.js dedup
f2 = base / 'web-front/src/stores/assistant.js'
js = f2.read_text(encoding='utf-8')
js = js.replace(
    "const key = p.trim();",
    "const key = p.trim().replace(/\\s+/g, ' ');"
)
f2.write_text(js, encoding='utf-8')
print('JS fixed')
