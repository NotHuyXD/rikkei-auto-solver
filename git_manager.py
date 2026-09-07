import os
import requests
import subprocess

class GitManager:
    def __init__(self, token):
        self.token = token
        self.headers = {
            "Authorization": f"token {self.token}",
            "Accept": "application/vnd.github.v3+json"
        }
        try:
            user_info = requests.get("https://api.github.com/user", headers=self.headers).json()
            self.username = user_info.get("login")
        except:
            self.username = None
            
    def check_repo_exists(self, repo_name):
        if not self.username:
            return False
        url = f"https://api.github.com/repos/{self.username}/{repo_name}"
        response = requests.get(url, headers=self.headers)
        return response.status_code == 200
        
    def create_repo(self, repo_name):
        url = "https://api.github.com/user/repos"
        data = {
            "name": repo_name,
            "private": False, # Để public để giáo viên có thể vào xem
            "auto_init": False
        }
        print(f"Đang tạo repository {repo_name} trên GitHub...")
        response = requests.post(url, headers=self.headers, json=data)
        
        if response.status_code == 201:
            print(f"Tạo thành công repo: {repo_name}")
            return response.json()['clone_url']
        elif response.status_code == 422: # Tồn tại rồi
            print(f"⚠️ Repo {repo_name} đã tồn tại trên GitHub.")
            return None
        else:
            print(f"Lỗi tạo repo: {response.status_code} - {response.text}")
            return None

    def push_code(self, workspace_dir, repo_link):
        print(f"Đang push code trong {workspace_dir} lên GitHub...")
        try:
            original_dir = os.getcwd()
            os.chdir(workspace_dir)
            
            # Khởi tạo git
            if not os.path.exists(".git"):
                subprocess.run(["git", "init"], check=True, capture_output=True)
            
            subprocess.run(["git", "add", "."], check=True, capture_output=True)
            
            # Commit code
            subprocess.run(["git", "commit", "-m", "homework"], capture_output=True)
            
            # Đổi nhánh sang main
            subprocess.run(["git", "branch", "-M", "main"], check=True, capture_output=True)
            
            # Xóa origin cũ nếu có
            subprocess.run(["git", "remote", "remove", "origin"], capture_output=True)
            
            # Thêm origin có nhúng Token để push tự động không cần đăng nhập tay
            auth_url = repo_link.replace("https://", f"https://{self.token}@")
            subprocess.run(["git", "remote", "add", "origin", auth_url], check=True, capture_output=True)
            
            # Push code
            result = subprocess.run(["git", "push", "-u", "origin", "main", "--force"], capture_output=True, text=True)
            
            if result.returncode == 0:
                print("Push code thành công!")
                os.chdir(original_dir)
                return True
            else:
                print(f"Lỗi git push: {result.stderr}")
                os.chdir(original_dir)
                return False
                
        except Exception as e:
            print(f"Lỗi khi push code: {e}")
            os.chdir(original_dir)
            return False
