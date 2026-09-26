import unittest

from app.llm.prompts import SYSTEM_PROMPT, analysis_prompt


class PromptTests(unittest.TestCase):
    def test_wrong_page_requires_recoverable_back_guidance(self):
        self.assertIn("action_type=back", SYSTEM_PROMPT)
        self.assertIn("当前页面与任务不一致", SYSTEM_PROMPT)
        self.assertIn("左上角返回", SYSTEM_PROMPT)
        self.assertIn("task_status=completed", SYSTEM_PROMPT)

    def test_analysis_prompt_includes_task_goal_and_recovery_rule(self):
        prompt = analysis_prompt("帮助用户查看淘宝物流", "senior", "session-1")
        self.assertIn("帮助用户查看淘宝物流", prompt)
        self.assertIn("回到任务路径", prompt)

if __name__ == "__main__":
    unittest.main()
