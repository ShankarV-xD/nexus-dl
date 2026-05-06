import sys
import yt_dlp
import json
import os

def get_python_version():
    return sys.version

def get_video_info(url):
    print(f"Python: Received URL for get_video_info: {url}")
    try:
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'ignoreerrors': True,
            'force_generic_extractor': False,
        }
        print("Python: Initialized yt-dlp options for full info")

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            print("Python: Created YoutubeDL instance")
            info_dict = ydl.extract_info(url, download=False)
            print("Python: extract_info finished")

            if not info_dict:
                print("Python: Failed to extract info_dict")
                return json.dumps({"error": "Failed to extract video info"})

            output_data = {
                "title": info_dict.get('title', 'Title not found'),
                "formats": info_dict.get('formats', []),
                "thumbnail": info_dict.get('thumbnail'),
                "duration_string": info_dict.get('duration_string'),
            }

            json_output = json.dumps(output_data, ensure_ascii=False)
            return json_output

    except yt_dlp.utils.DownloadError as e:
        error_message = f"YTDLP Error: {e}"
        print(error_message)
        return json.dumps({"error": error_message})
    except Exception as e:
        error_message = f"Unexpected Python Error: {e}"
        print(error_message)
        return json.dumps({"error": error_message})

# Global variables to track progress
_current_progress = {
    "percent": 0,
    "downloaded_bytes": 0,
    "total_bytes": 0,
    "speed": 0,
    "eta": 0,
    "status": "idle"
}

_current_phase = "idle"

def reset_progress_tracking():
    """Reset progress tracking variables"""
    global _current_progress, _current_phase
    _current_progress = {
        "percent": 0,
        "downloaded_bytes": 0,
        "total_bytes": 0,
        "speed": 0,
        "eta": 0,
        "status": "idle"
    }
    _current_phase = "idle"

def set_download_phase(phase):
    """Set the current download phase"""
    global _current_phase
    _current_phase = phase
    print(f"Python: Current phase set to {phase}")

def get_current_phase():
    """Get the current download phase"""
    return _current_phase

def get_current_progress():
    """Get the current download progress as JSON string"""
    return json.dumps(_current_progress)

def update_progress(progress_dict):
    """Update the progress tracking variables"""
    global _current_progress
    # Make a copy to avoid partial updates
    new_progress = _current_progress.copy()

    for key, value in progress_dict.items():
        if key in new_progress:
            new_progress[key] = value

    # Always ensure percent is between 0-100
    if 'percent' in new_progress and new_progress['percent'] is not None:
        new_progress['percent'] = max(0, min(100, new_progress['percent']))

    # Update global with complete new state
    _current_progress = new_progress

    # Debug output for tracking
    if 'percent' in progress_dict:
        print(f"Python: Progress updated to {new_progress['percent']}%")

def download_single_format_with_progress(url, format_id, output_dir):
    print(f"Python: Download with PROGRESS request. URL: {url}, Format ID: {format_id}, Output Dir: {output_dir}")
    temp_output_template = os.path.join(output_dir, '%(id)s_%(format_id)s.%(ext)s')

    final_filepath = None

    # Reset progress for this download
    global _current_progress
    _current_progress = {
        "percent": 0,
        "downloaded_bytes": 0,
        "total_bytes": 0,
        "speed": 0,
        "eta": 0,
        "status": "downloading"
    }

    def hook(d):
        nonlocal final_filepath
        global _current_progress # Ensure you're modifying the global

        if d['status'] == 'downloading':
            # Calculate percentage manually if 'percent' key is missing
            percent_val = None
            downloaded = d.get('downloaded_bytes')
            total = d.get('total_bytes') or d.get('total_bytes_estimate')

            if 'percent' in d: # Prefer direct key if yt-dlp provides it
                percent_val = d['percent']
            elif downloaded is not None and total is not None and total > 0:
                try:
                    percent_val = (downloaded * 100) / total
                except ZeroDivisionError:
                    percent_val = 0 # Should not happen if total > 0
            else:
                percent_val = _current_progress.get('percent', 0) # Keep previous if calculation fails

            # Update progress info using the calculated or provided percentage
            progress_update = {
                "percent": percent_val, # Use the calculated/retrieved value
                "downloaded_bytes": downloaded,
                "total_bytes": total,
                "speed": d.get('speed', 0),
                "eta": d.get('eta', 0),
                "status": "downloading"
            }
            update_progress(progress_update) # Update the global state

        elif d['status'] == 'finished':
            final_filepath = d.get('filename') or d.get('info_dict', {}).get('_filename')
            print(f"Python hook: Download finished. Captured Filename: {final_filepath}")
            # Ensure final progress is 100% and status is finished
            update_progress({
                "percent": 100,
                "status": "finished",
                "downloaded_bytes": _current_progress.get('total_bytes'), # Keep last known total
                "total_bytes": _current_progress.get('total_bytes'),
                "speed": 0,
                "eta": 0
            })

        elif d['status'] == 'error':
            print("Python hook: Error reported during download hook.")
            # Update status to error, keep other values as they were
            update_progress({"status": "error"})

    try:
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'ignoreerrors': True,
            'format': format_id,
            'outtmpl': temp_output_template,
            'progress_hooks': [hook],
            'overwrites': True,
        }
        print(f"Python: Download options for single format with progress: {ydl_opts}")

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            print("Python: Created YoutubeDL instance for download with progress")
            error_code = ydl.download([url])
            print(f"Python: ydl.download finished with code: {error_code}")

            if error_code == 0 and final_filepath and os.path.exists(final_filepath):
                print(f"Python: Download with progress successful. File: {final_filepath}")
                return json.dumps({
                    "status": "success",
                    "filepath": final_filepath
                })
            elif error_code != 0:
                print(f"Python: Download with progress failed with error code: {error_code}")
                return json.dumps({
                    "status": "error",
                    "message": f"yt-dlp download returned error code: {error_code}"
                })
            else:
                print(f"Python: Download with progress failed. Error code {error_code}, file path not captured or file missing: {final_filepath}")
                return json.dumps({
                    "status": "error",
                    "message": "Download finished but output file path could not be determined or found."
                })

    except yt_dlp.utils.DownloadError as e:
        error_message = f"YTDLP Download Error: {e}"
        print(error_message)
        return json.dumps({"status": "error", "message": error_message})
    except Exception as e:
        error_message = f"Unexpected Python Error during download with progress: {e}"
        print(error_message)
        return json.dumps({"status": "error", "message": error_message})
