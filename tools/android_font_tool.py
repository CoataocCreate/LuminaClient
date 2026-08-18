import os
import re
import tkinter as tk
from tkinter import filedialog, scrolledtext


def format_filename_for_android_res(name: str) -> str:
    base, ext = os.path.splitext(name)
    base = base.lower().replace(" ", "_").replace("-", "_")
    base = re.sub(r"[^a-z0-9_]", "", base)
    return base + ext


def rename_files_in_folder(folder_path: str, log_func):
    logs = []
    renamed = 0
    skipped = 0

    # os.scandir() is faster than os.listdir() + os.path.isfile()
    with os.scandir(folder_path) as entries:
        for entry in entries:
            if not entry.is_file():
                continue

            filename = entry.name
            new_filename = format_filename_for_android_res(filename)

            if filename == new_filename:
                skipped += 1
                continue

            old_path = entry.path
            new_path = os.path.join(folder_path, new_filename)

            try:
                os.rename(old_path, new_path)
                logs.append(f"Renamed: {filename} -> {new_filename}")
                renamed += 1
            except OSError as e:
                logs.append(f"Failed: {filename} -> {new_filename} ({e})")

    # Update GUI once instead of once per file
    if logs:
        log_func("\n".join(logs))

    log_func(f"Renamed: {renamed} | Skipped: {skipped}")


def browse_folder():
    folder_path = filedialog.askdirectory()

    if folder_path:
        folder_entry.delete(0, tk.END)
        folder_entry.insert(0, folder_path)


def start_renaming():
    folder_path = folder_entry.get()

    if not os.path.isdir(folder_path):
        log_output("Invalid folder path.")
        return

    start_button.config(state=tk.DISABLED)
    browse_button.config(state=tk.DISABLED)

    log_output(f"Starting renaming in: {folder_path}")

    try:
        rename_files_in_folder(folder_path, log_output)
        log_output("Renaming complete.")
    finally:
        start_button.config(state=tk.NORMAL)
        browse_button.config(state=tk.NORMAL)


def log_output(message):
    output_text.config(state=tk.NORMAL)
    output_text.insert(tk.END, message + "\n")
    output_text.see(tk.END)
    output_text.config(state=tk.DISABLED)


# GUI
root = tk.Tk()
root.title("Android Font Renamer")

tk.Label(root, text="Select Folder:").pack(padx=10, pady=(10, 0))

frame = tk.Frame(root)
frame.pack(padx=10, pady=5, fill=tk.X)

folder_entry = tk.Entry(frame, width=50)
folder_entry.pack(
    side=tk.LEFT,
    padx=(0, 5),
    expand=True,
    fill=tk.X
)

browse_button = tk.Button(
    frame,
    text="Browse",
    command=browse_folder
)
browse_button.pack(side=tk.RIGHT)

start_button = tk.Button(
    root,
    text="Start Renaming",
    command=start_renaming
)
start_button.pack(pady=5)

output_text = scrolledtext.ScrolledText(
    root,
    height=15,
    state=tk.DISABLED,
    bg="black",
    fg="lime",
    font=("Courier", 10)
)
output_text.pack(
    padx=10,
    pady=(5, 10),
    fill=tk.BOTH,
    expand=True
)

root.mainloop()