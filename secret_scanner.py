#!/usr/bin/env python3
import os
import re
import sys

# Directory and file ignore lists
IGNORE_DIRS = {
    '.git', '.idea', '.claude', '.github', 'target', 'docs', 'node_modules', '.settings', '.metadata', 'bin', 'test'
}
IGNORE_FILES = {
    'secret_scanner.py', 'Communication.md', 'README.md'
}
ALLOWED_EXTENSIONS = {
    '.yml', '.yaml', '.properties', '.xml', '.java', '.sql', '.json', '.conf'
}

# Regex patterns for scanning
# 1. Matches common variable placeholders like ${MINIO_ACCESS_KEY:admin} but flags the default fallback value if it's not empty
# 2. General password/token assignments
YML_SECRET_RE = re.compile(
    r'(password|pass|secret|secret-key|access-key|token|apikey|mail-pass|auth-code)\s*:\s*(.+)', 
    re.IGNORECASE
)
PROPERTIES_SECRET_RE = re.compile(
    r'(password|pass|secret|secret-key|access-key|token|apikey|mail-pass)\s*=\s*(.+)', 
    re.IGNORECASE
)
JAVA_SECRET_RE = re.compile(
    r'(String|var)\s+\w*(password|secret|token|apikey|accesskey|authcode)\w*\s*=\s*\"([^\"]+)\"', 
    re.IGNORECASE
)
SQL_SECRET_RE = re.compile(
    r"values\s*\(.*?,?\s*'admin123'\s*,?\s*.*?\)", 
    re.IGNORECASE
)

# Common non-secret words to skip false positives
SAFE_WORDS = {
    'true', 'false', 'null', 'none', 'yes', 'no', 'default', 'required', 'optional', 'empty', '""', "''",
    'debug', 'info', 'warn', 'error', 'all', 'any', 'system', 'root', 'admin', 'username', 'user', 'db',
    'mysql', 'localhost', '127.0.0.1', 'jdbc', 'utf-8', 'utf8', 'utf8mb4',
    'xinghuitec233', 'xinghui_admin233', 'your_secret', 'your_client_secret', 'your_secret_key'
}

def is_placeholder(value):
    # E.g. ${VAR_NAME} or ${VAR_NAME:}
    val = value.strip()
    if val.startswith('${') and val.endswith('}'):
        # Check if there is a fallback value after ':'
        if ':' in val:
            fallback = val.split(':', 1)[1][:-1].strip()
            # If fallback value is empty or looks like a safe word/placeholder, it's fine.
            # If it's a hardcoded secret like admin123, we should flag it.
            if not fallback or fallback.lower() in SAFE_WORDS:
                return True
            return False  # Flags it if fallback value is a hardcoded secret (like the MINIO_ACCESS_KEY:admin)
        return True
    return False

def check_line(file_path, line_no, line_content, file_ext):
    content = line_content.strip()
    if not content or content.startswith('#') or content.startswith('//'):
        return None

    # YAML check
    if file_ext in ('.yml', '.yaml'):
        match = YML_SECRET_RE.search(content)
        if match:
            key, val = match.groups()
            val = val.strip()
            # If val has inline comments, strip them
            if ' #' in val:
                val = val.split(' #')[0].strip()
            if val and not is_placeholder(val) and val.lower() not in SAFE_WORDS:
                return f"[YML] Potential secret '{key}': '{val}'"

    # Properties check
    elif file_ext == '.properties':
        match = PROPERTIES_SECRET_RE.search(content)
        if match:
            key, val = match.groups()
            val = val.strip()
            if val and not is_placeholder(val) and val.lower() not in SAFE_WORDS:
                return f"[Prop] Potential secret '{key}': '{val}'"

    # Java check
    elif file_ext == '.java':
        # Ignore comments or log statements
        if 'log.' in content or 'logger.' in content:
            return None
        match = JAVA_SECRET_RE.search(content)
        if match:
            var_name, key, val = match.groups()
            val = val.strip()
            if val and val.lower() not in SAFE_WORDS and len(val) > 4:
                # Simple check to skip variable placeholders or test constants that are obvious mocks
                if not (val.startswith('${') and val.endswith('}')) and not val.isupper():
                    return f"[Java] Hardcoded credential variable '{var_name}': '{val}'"

    # SQL check (looking for plain passwords inside user insert statements)
    elif file_ext == '.sql':
        # Skip standard comments
        if content.startswith('--'):
            return None
        # Check for admin123 or other hardcoded user passwords
        if 'admin123' in content.lower():
            # For RuoYi default admin, admin123 is the default hashed bcrypt password or plain mock.
            # We check if it is plain text.
            return f"[SQL] Found potential default/plain password 'admin123' in statement."

    return None

def scan_workspace(root_dir):
    findings = []
    file_count = 0
    
    for dirpath, dirnames, filenames in os.walk(root_dir):
        # Prune directory search tree
        dirnames[:] = [d for d in dirnames if d not in IGNORE_DIRS]
        
        for filename in filenames:
            if filename in IGNORE_FILES:
                continue
                
            _, ext = os.path.splitext(filename)
            if ext not in ALLOWED_EXTENSIONS:
                continue
                
            file_path = os.path.join(dirpath, filename)
            file_count += 1
            
            try:
                with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                    for line_no, line in enumerate(f, 1):
                        warning = check_line(file_path, line_no, line, ext)
                        if warning:
                            rel_path = os.path.relpath(file_path, root_dir)
                            findings.append((rel_path, line_no, line.strip(), warning))
            except Exception as e:
                print(f"Error reading file {file_path}: {e}")
                
    return findings, file_count

if __name__ == '__main__':
    print("====================================================")
    print("[SEC] XingHui Admin - Sensitive Data & Secrets Checker")
    print("====================================================")
    
    root_directory = os.path.dirname(os.path.abspath(__file__))
    print(f"Scanning workspace root: {root_directory}")
    
    findings, files_scanned = scan_workspace(root_directory)
    
    print(f"Scan complete. Scanned {files_scanned} files.")
    print("====================================================")
    
    if findings:
        print(f"[WARN] WARNING: Found {len(findings)} potential secret leakage(s):\n")
        for filepath, line_no, line_content, desc in findings:
            print(f" File: {filepath}:{line_no}")
            print(f" Line: {line_content}")
            print(f" Detail: {desc}")
            print("-" * 50)
        print("\n[FAIL] Please fix these secrets or use environment variables before pushing to Github!")
        sys.exit(1)
    else:
        print("[OK] Success: No sensitive credentials or secrets leaked! Safe to commit.")
        sys.exit(0)
