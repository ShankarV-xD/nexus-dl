import sys
import yt_dlp
import json
import os
import threading

def get_python_version():
    return sys.version


def _is_bot_detection_error(error_str):
    return ('Sign in to confirm' in error_str or
            ('bot' in error_str.lower() and 'cookies' in error_str.lower()))


def _map_error(error_str):
    """Convert raw yt-dlp error strings into readable user messages."""
    if 'login required' in error_str.lower() or 'rate-limit reached' in error_str.lower() or 'log in' in error_str.lower():
        return "This platform requires you to be logged in. Instagram and Facebook often restrict access to authenticated users only."
    if 'Remote end closed connection' in error_str or 'TransportError' in error_str or 'Connection reset' in error_str:
        return "Connection refused by the server. This platform may be blocking automated requests from your network. Try again later or test on a real device."
    if 'HTTP Error 403' in error_str or 'Forbidden' in error_str:
        return "Access denied (403). The video may be age-restricted or region-locked."
    if 'HTTP Error 404' in error_str or 'Not Found' in error_str:
        return "Video not found (404). It may have been removed or the URL is invalid."
    if 'Video unavailable' in error_str:
        return "This video is unavailable. It may be private, deleted, or region-blocked."
    if 'SSL' in error_str or 'certificate' in error_str.lower():
        return "SSL error: could not verify the server's certificate. Check your network connection."
    if 'Unsupported URL' in error_str:
        return "This URL is not supported. NexusDL supports YouTube, Instagram, Facebook, TikTok, and Twitter."
    return f"Could not load video: {error_str}"


def _extract_subtitle_options(info_dict):
    """Return sorted list of {lang_code, lang_name, is_auto} dicts."""
    result = []
    seen = set()

    for lang_code, formats in info_dict.get('subtitles', {}).items():
        if not formats:
            continue
        lang_name = next((f.get('name') for f in formats if f.get('name')), lang_code)
        result.append({'lang_code': lang_code, 'lang_name': lang_name, 'is_auto': False})
        seen.add(lang_code)

    for lang_code, formats in info_dict.get('automatic_captions', {}).items():
        if lang_code in seen or not formats:
            continue
        lang_name = next((f.get('name') for f in formats if f.get('name')), lang_code)
        result.append({'lang_code': lang_code, 'lang_name': f"{lang_name} (auto)", 'is_auto': True})

    result.sort(key=lambda x: (x['is_auto'], x['lang_name'].lower()))
    manual = sum(1 for x in result if not x['is_auto'])
    auto = len(result) - manual
    print(f"Python: Subtitle options: {manual} manual, {auto} auto-generated ({len(result)} total)")
    return result


def get_video_info(url, cookiefile=None):
    print(f"Python: Received URL for get_video_info: {url}")

    if not url or not isinstance(url, str):
        return json.dumps({"error": "Invalid URL: URL must be a non-empty string"})

    url = url.strip()
    if not url.startswith(('http://', 'https://')):
        return json.dumps({"error": "Invalid URL: Only HTTP and HTTPS protocols are supported"})

    blocked_patterns = ['localhost', '127.0.0.1', '0.0.0.0', '::1', 'file://', 'content://']
    if any(pattern in url.lower() for pattern in blocked_patterns):
        return json.dumps({"error": "Security: URL pattern is not allowed"})

    # Strategy 1: web client → full adaptive DASH streams (best quality/variety)
    # Strategy 2: android fallback → limited pre-muxed formats, no bot-detection
    strategies = [
        ('web (default)', {}),
        ('android fallback', {'extractor_args': {'youtube': {'player_client': ['android']}}}),
    ]

    info_dict = None
    last_error = None

    for label, extra_opts in strategies:
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'force_generic_extractor': False,
            'noplaylist': True,
            'nocheckcertificate': True,  # Android SSL cert bundle lacks some CDN chains
            'http_headers': {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
                'Accept-Language': 'en-US,en;q=0.9',
            },
            **extra_opts,
        }
        if cookiefile:
            ydl_opts['cookiefile'] = cookiefile

        try:
            print(f"Python: Trying strategy: {label}")
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info_dict = ydl.extract_info(url, download=False)
            print(f"Python: extract_info succeeded with strategy: {label}")
            break
        except yt_dlp.utils.DownloadError as e:
            error_str = str(e)
            print(f"Python: Strategy '{label}' failed: {error_str[:120]}")
            last_error = e
            if _is_bot_detection_error(error_str):
                continue  # try next strategy
            # Non-bot error — map to user-friendly message and surface directly
            return json.dumps({"error": _map_error(error_str)})
        except Exception as e:
            return json.dumps({"error": f"Unexpected error: {str(e)}"})

    if info_dict is None:
        return json.dumps({"error": _map_error(str(last_error))})

    all_formats = info_dict.get('formats', [])
    print(f"Python: yt-dlp returned {len(all_formats)} raw formats")

    valid_formats = []
    for fmt in all_formats:
        format_id = fmt.get('format_id')
        format_url = fmt.get('url')
        if not format_id or not format_url:
            continue
        # Drop image-only storyboard sequences
        if fmt.get('vcodec') == 'none' and fmt.get('acodec') == 'none':
            continue
        if fmt.get('format_note', '') and 'storyboard' in fmt.get('format_note', '').lower():
            continue
        valid_formats.append(fmt)

    print(f"Python: After filter: {len(valid_formats)} valid formats")

    output_data = {
        "title": info_dict.get('title', 'Title not found'),
        "formats": valid_formats,
        "thumbnail": info_dict.get('thumbnail'),
        "duration_string": info_dict.get('duration_string'),
        "uploader": info_dict.get('uploader') or info_dict.get('channel'),
        "channel": info_dict.get('channel') or info_dict.get('uploader'),
        "subtitles": _extract_subtitle_options(info_dict),
    }

    return json.dumps(output_data, ensure_ascii=False)


# Module-level lock for thread-safe progress access
_progress_lock = threading.Lock()

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
    global _current_progress, _current_phase
    with _progress_lock:
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
    global _current_phase
    _current_phase = phase
    print(f"Python: Current phase set to {phase}")


def get_current_phase():
    return _current_phase


def get_current_progress():
    with _progress_lock:
        return json.dumps(_current_progress.copy())


def update_progress(progress_dict):
    global _current_progress
    with _progress_lock:
        new_progress = _current_progress.copy()
        for key, value in progress_dict.items():
            if key in new_progress:
                new_progress[key] = value
        if 'percent' in new_progress and new_progress['percent'] is not None:
            new_progress['percent'] = max(0, min(100, new_progress['percent']))
        _current_progress = new_progress
    if 'percent' in progress_dict and new_progress.get('percent') is not None:
        print(f"Python: Progress updated to {new_progress['percent']}%")


def download_single_format_with_progress(url, format_id, output_dir, cookiefile=None):
    global _current_progress
    print(f"Python: Download with PROGRESS request. URL: {url}, Format ID: {format_id}, Output Dir: {output_dir}")

    if not format_id or not isinstance(format_id, str):
        error_msg = f"Invalid format_id provided: {format_id}"
        print(f"Python Error: {error_msg}")
        return json.dumps({"status": "error", "message": error_msg})

    temp_output_template = os.path.join(output_dir, '%(id)s_%(format_id)s.%(ext)s')
    final_filepath = None

    with _progress_lock:
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
        if d['status'] == 'downloading':
            downloaded = d.get('downloaded_bytes')
            total = d.get('total_bytes') or d.get('total_bytes_estimate')
            if 'percent' in d:
                percent_val = d['percent']
            elif downloaded is not None and total is not None and total > 0:
                try:
                    percent_val = (downloaded * 100) / total
                except ZeroDivisionError:
                    percent_val = 0
            else:
                with _progress_lock:
                    percent_val = _current_progress.get('percent', 0)
            update_progress({
                "percent": percent_val,
                "downloaded_bytes": downloaded,
                "total_bytes": total,
                "speed": d.get('speed', 0),
                "eta": d.get('eta', 0),
                "status": "downloading"
            })
        elif d['status'] == 'finished':
            final_filepath = d.get('filename') or d.get('info_dict', {}).get('_filename')
            print(f"Python hook: Download finished. Captured Filename: {final_filepath}")
            with _progress_lock:
                current_total = _current_progress.get('total_bytes')
            update_progress({
                "percent": 100,
                "status": "finished",
                "downloaded_bytes": current_total,
                "total_bytes": current_total,
                "speed": 0,
                "eta": 0
            })
        elif d['status'] == 'error':
            print("Python hook: Error reported during download hook.")
            update_progress({"status": "error"})

    # Same strategy as get_video_info: web first, android fallback on bot-detection
    strategies = [
        ('web (default)', {}),
        ('android fallback', {'extractor_args': {'youtube': {'player_client': ['android']}}}),
    ]

    for label, extra_opts in strategies:
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'format': format_id,
            'outtmpl': temp_output_template,
            'progress_hooks': [hook],
            'overwrites': True,
            'nocheckcertificate': True,  # Android SSL cert bundle lacks some CDN chains
            'http_headers': {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
                'Accept-Language': 'en-US,en;q=0.9',
            },
            **extra_opts,
        }
        if cookiefile:
            ydl_opts['cookiefile'] = cookiefile

        try:
            print(f"Python: Download strategy: {label}")
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
            print(f"Python: extract_info(download=True) finished with strategy: {label}")

            actual_filepath = None
            if info:
                requested = info.get('requested_downloads', [])
                if requested:
                    actual_filepath = requested[0].get('filepath')
            if not actual_filepath:
                actual_filepath = final_filepath
            if not actual_filepath or not os.path.exists(actual_filepath):
                video_id = info.get('id', '') if info else ''
                try:
                    for fname in os.listdir(output_dir):
                        if format_id in fname and (not video_id or video_id in fname):
                            candidate = os.path.join(output_dir, fname)
                            if os.path.isfile(candidate):
                                actual_filepath = candidate
                                break
                except Exception as scan_err:
                    print(f"Python: Error scanning output dir: {scan_err}")

            if actual_filepath and os.path.exists(actual_filepath):
                print(f"Python: Download successful. File: {actual_filepath}")
                return json.dumps({"status": "success", "filepath": actual_filepath})
            else:
                print(f"Python: Download failed. File not found: {actual_filepath}")
                return json.dumps({
                    "status": "error",
                    "message": "Download finished but output file path could not be determined or found."
                })

        except yt_dlp.utils.DownloadError as e:
            error_str = str(e)
            print(f"Python: Download strategy '{label}' failed: {error_str[:120]}")
            if _is_bot_detection_error(error_str):
                update_progress({"status": "error"})
                continue  # retry with next strategy
            # Map known errors to user-friendly messages
            if "Requested format is not available" in error_str:
                error_message = f"Format '{format_id}' is no longer available. Please go back and refresh the format list, then try a different format."
            elif "format not available" in error_str.lower():
                error_message = "The selected format is not available. Please try another format."
            elif "login required" in error_str.lower() or "rate-limit reached" in error_str.lower() or "log in" in error_str.lower():
                error_message = "This platform requires you to be logged in to download this content. Instagram and Facebook often restrict access to authenticated users only."
            elif "Remote end closed connection" in error_str or "Connection reset" in error_str or "TransportError" in error_str:
                error_message = "Connection was refused by the server. This platform may be blocking automated requests from your network. Try again later or test on a real device."
            elif "HTTP Error 403" in error_str or "Forbidden" in error_str:
                error_message = "Access denied (403). The video may be age-restricted or require authentication."
            elif "HTTP Error 404" in error_str or "Not Found" in error_str:
                error_message = "Video not found (404). The video may have been removed."
            elif "Video unavailable" in error_str:
                error_message = "This video is unavailable. It may be private, deleted, or region-blocked."
            elif "SSL" in error_str or "certificate" in error_str.lower():
                error_message = "SSL Certificate Error: problem verifying the server's security certificate."
            else:
                error_message = f"Download Error: {error_str}"
            print(f"Python Download Error: {error_message}")
            update_progress({"status": "error"})
            return json.dumps({"status": "error", "message": error_message})
        except Exception as e:
            error_message = f"Unexpected Python Error during download: {str(e)}"
            print(error_message)
            update_progress({"status": "error"})
            return json.dumps({"status": "error", "message": error_message})

    # All strategies exhausted (only happens when all attempts hit bot detection)
    update_progress({"status": "error"})
    return json.dumps({"status": "error", "message": "YouTube is requiring sign-in to confirm you're not a bot. Try again later or use a different network."})


def download_subtitle(url, lang_code, is_auto, output_dir, cookiefile=None):
    """Download subtitle as .srt/.vtt file. Returns JSON with filepath on success."""
    print(f"Python: Download subtitle. URL: {url}, Lang: {lang_code}, Auto: {bool(is_auto)}")

    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'writesubtitles': not bool(is_auto),
        'writeautomaticsub': bool(is_auto),
        'subtitleslangs': [str(lang_code)],
        'subtitlesformat': 'srt/vtt/best',
        'outtmpl': os.path.join(output_dir, 'sub_%(id)s'),
        'nocheckcertificate': True,  # Android SSL cert bundle lacks some CDN chains
    }
    if cookiefile:
        ydl_opts['cookiefile'] = cookiefile

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.extract_info(url, download=True)

        lang_str = str(lang_code)
        for fname in os.listdir(output_dir):
            full_path = os.path.join(output_dir, fname)
            if (fname.startswith('sub_') and
                    f'.{lang_str}.' in fname and
                    fname.endswith(('.srt', '.vtt')) and
                    os.path.isfile(full_path)):
                print(f"Python: Subtitle found: {full_path}")
                return json.dumps({"status": "success", "filepath": full_path})

        print("Python: Subtitle file not found after download")
        return json.dumps({"status": "error", "message": "Subtitle not found. The video may not have subtitles in this language."})

    except Exception as e:
        error_str = str(e)
        print(f"Python: Subtitle error: {error_str[:200]}")
        return json.dumps({"status": "error", "message": f"Subtitle download failed: {error_str[:300]}"})
