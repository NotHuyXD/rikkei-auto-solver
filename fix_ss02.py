import os
import subprocess
from dotenv import load_dotenv

load_dotenv()
username = os.getenv("GITHUB_USERNAME")
token = os.getenv("GITHUB_TOKEN")

workspace = r"C:\Users\motmi\.gemini\antigravity-ide\scratch\rikkei-auto-solver\solution_workspace"

print("Đang gắn lại remote và push cho Session 02...")
for i in range(1, 6):
    repo_name = f"IT213-SS02-BAI{i}"
    repo_path = os.path.join(workspace, repo_name)
    if os.path.isdir(repo_path):
        os.chdir(repo_path)
        url = f"https://{token}@github.com/{username}/{repo_name}.git"
        
        # Bỏ qua lỗi nếu remote đã tồn tại
        subprocess.run(["git", "remote", "remove", "origin"], stderr=subprocess.DEVNULL)
        subprocess.run(["git", "remote", "add", "origin", url])
        
        result = subprocess.run(["git", "push", "origin", "main", "--force"], capture_output=True, text=True)
        if result.returncode == 0:
            print(f"✅ Fixed and pushed {repo_name}")
        else:
            print(f"❌ Failed to push {repo_name}: {result.stderr}")
