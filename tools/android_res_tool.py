import os
import re
import tkinter as tk
from tkinter import filedialog, ttk, scrolledtext


# ---------- Utility Functions ----------

def log(message):
    output_text.config(state=tk.NORMAL)
    output_text.insert(tk.END, message + "\n")
    output_text.see(tk.END)
    output_text.config(state=tk.DISABLED)


def log_batch(messages):
    """Write many log messages to Tkinter at once."""
    if not messages:
        return

    output_text.config(state=tk.NORMAL)
    output_text.insert(tk.END, "\n".join(messages) + "\n")
    output_text.see(tk.END)
    output_text.config(state=tk.DISABLED)


def format_filename_for_android_res(name: str) -> str:
    base, ext = os.path.splitext(name)

    base = base.lower().replace(" ", "_").replace("-", "_")
    base = re.sub(r"[^a-z0-9_]", "", base)

    return base + ext


def rename_files_in_folder(folder_path: str, filter_exts=None):
    if not os.path.isdir(folder_path):
        log("[Error] Invalid folder path.")
        return

    renamed_count = 0
    skipped_count = 0
    failed_count = 0

    logs = []

    # os.scandir() is faster than os.listdir() + os.path.isfile()
    with os.scandir(folder_path) as entries:
        for entry in entries:

            # Skip directories
            if not entry.is_file():
                continue

            filename = entry.name

            # Extension filter
            if filter_exts and not filename.lower().endswith(filter_exts):
                continue

            new_filename = format_filename_for_android_res(filename)

            # Already correctly formatted
            if filename == new_filename:
                logs.append(f"[Skipped] {filename}")
                skipped_count += 1
                continue

            old_path = entry.path
            new_path = os.path.join(folder_path, new_filename)

            # Prevent overwriting another existing file
            if os.path.exists(new_path):
                logs.append(
                    f"[Skipped] {filename} → {new_filename} "
                    f"(target already exists)"
                )
                skipped_count += 1
                continue

            try:
                os.rename(old_path, new_path)

                logs.append(
                    f"[Renamed] {filename} → {new_filename}"
                )

                renamed_count += 1

            except OSError as e:
                logs.append(
                    f"[Failed] {filename}: {e}"
                )
                failed_count += 1

    # Update GUI only once
    log_batch(logs)

    log(
        f"Done. Renamed: {renamed_count}, "
        f"Skipped: {skipped_count}, "
        f"Failed: {failed_count}"
    )


def browse_folder(entry_widget):
    folder = filedialog.askdirectory()

    if folder:
        entry_widget.delete(0, tk.END)
        entry_widget.insert(0, folder)


# ---------- GUI Setup ----------

root = tk.Tk()
root.title("Android Resource Cleaner")
root.geometry("720x500")


# ---------- Tabs ----------

tabs = ttk.Notebook(root)
tabs.pack(fill=tk.BOTH, expand=True)


# ---------- Rename Fonts Tab ----------

font_tab = ttk.Frame(tabs)
tabs.add(font_tab, text="Rename Fonts")

font_frame = tk.Frame(font_tab, pady=10)
font_frame.pack(fill=tk.X, padx=10)

font_entry = tk.Entry(font_frame, width=50)
font_entry.pack(
    side=tk.LEFT,
    expand=True,
    fill=tk.X,
    padx=(0, 5)
)

tk.Button(
    font_frame,
    text="Browse",
    command=lambda: browse_folder(font_entry)
).pack(side=tk.RIGHT)


tk.Button(
    font_tab,
    text="Start Renaming Fonts",
    command=lambda: rename_files_in_folder(
        font_entry.get(),
        (".ttf", ".otf")
    )
).pack(pady=5)


# ---------- Clean All File Names Tab ----------

clean_tab = ttk.Frame(tabs)
tabs.add(clean_tab, text="Clean File Names")

clean_frame = tk.Frame(clean_tab, pady=10)
clean_frame.pack(fill=tk.X, padx=10)

clean_entry = tk.Entry(clean_frame, width=50)
clean_entry.pack(
    side=tk.LEFT,
    expand=True,
    fill=tk.X,
    padx=(0, 5)
)

tk.Button(
    clean_frame,
    text="Browse",
    command=lambda: browse_folder(clean_entry)
).pack(side=tk.RIGHT)


tk.Button(
    clean_tab,
    text="Start Cleaning File Names",
    command=lambda: rename_files_in_folder(
        clean_entry.get()
    )
).pack(pady=5)


# ---------- Logs Tab ----------

log_tab = ttk.Frame(tabs)
tabs.add(log_tab, text="Logs")

output_text = scrolledtext.ScrolledText(
    log_tab,
    height=20,
    state=tk.DISABLED,
    bg="black",
    fg="lime",
    font=("Courier", 10)
)

output_text.pack(
    fill=tk.BOTH,
    expand=True,
    padx=10,
    pady=10
)


# ---------- Start ----------

root.mainloop()
