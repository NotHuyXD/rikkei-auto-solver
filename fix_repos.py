import os
import subprocess

workspace = r"C:\Users\motmi\.gemini\antigravity-ide\scratch\rikkei-auto-solver\solution_workspace"
original_cwd = os.getcwd()

print("Bắt đầu dọn dẹp và sửa lại 26 repo...")

for repo_name in os.listdir(workspace):
    repo_path = os.path.join(workspace, repo_name)
    if not os.path.isdir(repo_path):
        continue
    
    # Tìm tên bài tập từ tên repo (vd: IT213-SS02-BAI1 -> bai1)
    ex_name = repo_name.split("-")[-1].lower()
    
    os.chdir(repo_path)
    
    # Đổi tên file
    files = [f for f in os.listdir() if os.path.isfile(f) and not f.startswith('.')]
    if files:
        code_file = files[0]
        ext = os.path.splitext(code_file)[1]
        new_name = f"{ex_name}{ext}"
        
        if code_file != new_name:
            os.rename(code_file, new_name)
            
    # Xử lý Git
    subprocess.run(["git", "add", "."], capture_output=True)
    # Ghi đè commit cũ, đổi message thành "homework"
    subprocess.run(["git", "commit", "--amend", "-m", "homework"], capture_output=True)
    
    # Push đè lên GitHub
    result = subprocess.run(["git", "push", "origin", "main", "--force"], capture_output=True, text=True)
    if result.returncode == 0:
        print(f"✅ Đã sửa và push lại {repo_name}")
    else:
        print(f"❌ Lỗi push {repo_name}: {result.stderr}")

os.chdir(original_cwd)
print("Hoàn tất!")
