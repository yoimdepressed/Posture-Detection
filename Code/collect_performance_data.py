#!/usr/bin/env python3
"""
Performance Data Collection Script for Posture Analyzer
Fetches data from Firebase and generates comparison tables
"""

import json
import time
from datetime import datetime

# Firebase REST API endpoint
FIREBASE_URL = "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app"

def fetch_firebase_data():
    """Fetch latest performance data from Firebase"""
    try:
        import urllib.request
        
        # Fetch all performance data
        url = f"{FIREBASE_URL}/performance_data.json"
        with urllib.request.urlopen(url) as response:
            data = json.loads(response.read().decode())
        
        # Handle nested structure - data might be under "performance_data" key
        if data and "performance_data" in data:
            return data["performance_data"]
        
        return data
    except Exception as e:
        print(f"Error fetching data: {e}")
        return None

def get_latest_session_by_processor(data, target_device=None):
    """Extract latest session data for each processor (highest totalInferences)"""
    results = {}
    
    if not data:
        return results
    
    print(f"\nSearching for data{f' from device: {target_device}' if target_device else ''}...")
    
    for processor, devices in data.items():
        if not isinstance(devices, dict):
            continue
            
        # Find session with highest totalInferences (most complete)
        best_session = None
        max_inferences = 0
        device_name = ""
        
        for device, timestamps in devices.items():
            if not isinstance(timestamps, dict):
                continue
            
            # Skip if target_device specified and this isn't it
            if target_device and device != target_device:
                continue
            
            for timestamp, session_data in timestamps.items():
                try:
                    # Skip non-numeric keys
                    int(timestamp)
                    
                    if not isinstance(session_data, dict):
                        continue
                    
                    total = session_data.get('totalInferences', 0)
                    if total > max_inferences:
                        max_inferences = total
                        best_session = session_data
                        device_name = device
                        
                except (ValueError, TypeError):
                    # Skip non-timestamp keys
                    continue
        
        if best_session and max_inferences > 0:
            results[processor] = best_session
            print(f"  ✓ {processor}: {max_inferences} inferences (device: {device_name})")
        else:
            print(f"  ✗ {processor}: No data found")
    
    return results

def format_time(microseconds):
    """Convert microseconds to milliseconds"""
    return microseconds / 1000.0

def print_comparison_table(cpu_data, gpu_data, nnapi_data):
    """Print formatted comparison table"""
    
    print("\n" + "="*80)
    print("PERFORMANCE COMPARISON TABLE - 1 MINUTE PER DELEGATE")
    print("="*80)
    print()
    
    # Header
    print(f"{'Metric':<35} {'CPU':<15} {'GPU':<15} {'NPU/NNAPI':<15}")
    print("-"*80)
    
    # Extract data
    metrics = [
        ("Average Inference Time (ms)", "avgInferenceTime", format_time),
        ("Min Inference Time (ms)", "minInferenceTime", format_time),
        ("Max Inference Time (ms)", "maxInferenceTime", format_time),
        ("P95 Inference Time (ms)", "p95InferenceTime", format_time),
        ("Average FPS", "avgFps", lambda x: x),
        ("Total Inferences", "totalInferences", lambda x: x),
        ("Model Load Time (ms)", "modelLoadTime", lambda x: x),
        ("Warmup Time (ms)", "warmupTime", lambda x: x),
    ]
    
    for label, key, formatter in metrics:
        cpu_val = formatter(cpu_data.get(key, 0)) if cpu_data else "N/A"
        gpu_val = formatter(gpu_data.get(key, 0)) if gpu_data else "N/A"
        nnapi_val = formatter(nnapi_data.get(key, 0)) if nnapi_data else "N/A"
        
        # Format based on type
        if isinstance(cpu_val, (int, float)) and cpu_val != "N/A":
            print(f"{label:<35} {cpu_val:<15.2f} {str(gpu_val):<15} {str(nnapi_val):<15}")
        else:
            print(f"{label:<35} {str(cpu_val):<15} {str(gpu_val):<15} {str(nnapi_val):<15}")
    
    print("-"*80)
    
    # Device info
    if cpu_data:
        print(f"\nDevice: {cpu_data.get('deviceName', 'N/A')}")
        print(f"Model: {cpu_data.get('deviceModel', 'N/A')}")
        print(f"Android: {cpu_data.get('androidVersion', 'N/A')}")
    
    print("\n" + "="*80)
    print()

def print_instructions():
    """Print test instructions"""
    print("\n" + "="*80)
    print("PERFORMANCE DATA COLLECTION INSTRUCTIONS")
    print("="*80)
    print()
    print("⏱️  TIMING GUIDE:")
    print()
    print("1. START: Launch the Posture Analyzer app")
    print("   → App starts with CPU delegate by default")
    print("   → Let it run for exactly 60 seconds")
    print("   → Keep the camera facing a person for continuous detection")
    print()
    print("2. MINUTE 1 (0:00 - 1:00): CPU Testing")
    print("   → Default delegate is CPU")
    print("   → Just let it run")
    print("   → Data is being uploaded to Firebase automatically")
    print()
    print("3. MINUTE 2 (1:00 - 2:00): GPU Testing")
    print("   → At 1:00, switch to GPU delegate:")
    print("     • Tap the GPU radio button in the app")
    print("   → Let it run for 60 seconds")
    print()
    print("4. MINUTE 3 (2:00 - 3:00): NPU/NNAPI Testing")
    print("   → At 2:00, switch to NNAPI delegate:")
    print("     • Tap the NNAPI radio button in the app")
    print("   → Let it run for 60 seconds")
    print()
    print("5. DONE (3:00): Stop and Fetch Results")
    print("   → Run this script to fetch and display results")
    print()
    print("📋 CHECKLIST:")
    print("   ✓ Camera is on and detecting a person")
    print("   ✓ Phone/device is plugged in (to avoid thermal throttling)")
    print("   ✓ Close other apps (for consistent performance)")
    print("   ✓ Keep the device in the same position for all tests")
    print()
    print("="*80)
    print()

def countdown_timer(minutes, label):
    """Display countdown timer"""
    total_seconds = minutes * 60
    for remaining in range(total_seconds, 0, -1):
        mins = remaining // 60
        secs = remaining % 60
        print(f"\r{label}: {mins:02d}:{secs:02d} remaining", end="", flush=True)
        time.sleep(1)
    print(f"\r{label}: COMPLETE! ✓" + " "*20)

def main():
    import sys
    
    if len(sys.argv) > 1 and sys.argv[1] == "guide":
        # Show guided test mode
        print_instructions()
        
        response = input("Ready to start 3-minute guided test? (y/n): ")
        if response.lower() != 'y':
            print("Test cancelled.")
            return
        
        print("\n🚀 Starting guided test...\n")
        print("=" * 80)
        
        # Minute 1: CPU
        print("\n📱 STEP 1: Make sure the app is running (CPU is default)")
        input("Press ENTER when ready to start CPU test...")
        countdown_timer(1, "CPU Test")
        
        # Minute 2: GPU
        print("\n\n📱 STEP 2: Switch to GPU delegate NOW!")
        input("Press ENTER after switching to GPU...")
        countdown_timer(1, "GPU Test")
        
        # Minute 3: NNAPI
        print("\n\n📱 STEP 3: Switch to NNAPI delegate NOW!")
        input("Press ENTER after switching to NNAPI...")
        countdown_timer(1, "NNAPI Test")
        
        print("\n\n✓ All tests complete! Fetching results...\n")
    else:
        print_instructions()
    
    # Fetch and display results
    print("Fetching data from Firebase...")
    data = fetch_firebase_data()
    
    if not data:
        print("❌ No data found in Firebase")
        print("\nMake sure:")
        print("  1. You've run the app with all three delegates")
        print("  2. Each delegate ran for at least 1 minute")
        print("  3. Firebase has internet connectivity")
        return
    
    # Check for device argument
    target_device = None
    if len(sys.argv) > 2 and sys.argv[2]:
        target_device = sys.argv[2]
    
    # Extract latest sessions
    sessions = get_latest_session_by_processor(data, target_device)
    
    cpu_data = sessions.get("CPU")
    gpu_data = sessions.get("GPU")
    nnapi_data = sessions.get("NPU_NNAPI")
    
    if not cpu_data and not gpu_data and not nnapi_data:
        print("\n❌ No valid session data found")
        print("\nAvailable devices in Firebase:")
        for processor, devices in data.items():
            if isinstance(devices, dict):
                print(f"  {processor}: {', '.join(devices.keys())}")
        print(f"\nTip: Run script with device name: python3 collect_performance_data.py fetch <device_name>")
        return
    
    # Print results
    print_comparison_table(cpu_data, gpu_data, nnapi_data)
    
    # Save to file
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"performance_results_{timestamp}.txt"
    
    with open(filename, "w") as f:
        f.write("PERFORMANCE COMPARISON TABLE - 1 MINUTE PER DELEGATE\n")
        f.write("="*80 + "\n\n")
        
        f.write(f"{'Metric':<35} {'CPU':<15} {'GPU':<15} {'NPU/NNAPI':<15}\n")
        f.write("-"*80 + "\n")
        
        metrics = [
            ("Average Inference Time (ms)", "avgInferenceTime", format_time),
            ("Min Inference Time (ms)", "minInferenceTime", format_time),
            ("Max Inference Time (ms)", "maxInferenceTime", format_time),
            ("P95 Inference Time (ms)", "p95InferenceTime", format_time),
            ("Average FPS", "avgFps", lambda x: x),
            ("Total Inferences", "totalInferences", lambda x: x),
            ("Model Load Time (ms)", "modelLoadTime", lambda x: x),
            ("Warmup Time (ms)", "warmupTime", lambda x: x),
        ]
        
        for label, key, formatter in metrics:
            cpu_val = formatter(cpu_data.get(key, 0)) if cpu_data else "N/A"
            gpu_val = formatter(gpu_data.get(key, 0)) if gpu_data else "N/A"
            nnapi_val = formatter(nnapi_data.get(key, 0)) if nnapi_data else "N/A"
            
            # Format based on type
            if isinstance(cpu_val, (int, float)) and cpu_val != "N/A":
                f.write(f"{label:<35} {cpu_val:<15.2f} {str(gpu_val):<15} {str(nnapi_val):<15}\n")
            else:
                f.write(f"{label:<35} {str(cpu_val):<15} {str(gpu_val):<15} {str(nnapi_val):<15}\n")
    
    print(f"✓ Results saved to: {filename}")

if __name__ == "__main__":
    main()
