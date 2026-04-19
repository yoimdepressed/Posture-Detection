#!/usr/bin/env python3
"""
Device Performance Comparison Script
Compares performance metrics across different devices and delegates
Uses last 100 recordings from each device/delegate combination
"""

import json
import urllib.request
from collections import defaultdict

# Firebase REST API endpoint
FIREBASE_URL = "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app"

def fetch_firebase_data():
    """Fetch all performance data from Firebase"""
    try:
        url = f"{FIREBASE_URL}/performance_data.json"
        with urllib.request.urlopen(url) as response:
            data = json.loads(response.read().decode())
        
        # Handle nested structure
        if data and "performance_data" in data:
            return data["performance_data"]
        
        return data
    except Exception as e:
        print(f"Error fetching data: {e}")
        return None

def get_last_n_sessions(data, n=100):
    """
    Get last N sessions for each device/delegate combination
    Returns: dict[device][delegate] = list of session data (last N entries)
    """
    device_data = defaultdict(lambda: defaultdict(list))
    
    if not data:
        return device_data
    
    for processor, devices in data.items():
        if not isinstance(devices, dict):
            continue
        
        for device, timestamps in devices.items():
            if not isinstance(timestamps, dict):
                continue
            
            # Collect all sessions with timestamps
            sessions = []
            for timestamp, session_data in timestamps.items():
                try:
                    ts = int(timestamp)
                    if isinstance(session_data, dict):
                        sessions.append((ts, session_data))
                except (ValueError, TypeError):
                    continue
            
            # Sort by timestamp (newest first) and take last N
            sessions.sort(key=lambda x: x[0], reverse=True)
            last_n = [s[1] for s in sessions[:n]]
            
            if last_n:
                device_data[device][processor] = last_n
    
    return device_data

def calculate_averages(sessions):
    """Calculate average metrics from a list of sessions"""
    if not sessions:
        return None
    
    metrics = {
        'avgInferenceTime': 0,
        'minInferenceTime': 0,
        'maxInferenceTime': 0,
        'p95InferenceTime': 0,
        'avgFps': 0,
        'totalInferences': 0,
        'modelLoadTime': 0,
        'count': len(sessions)
    }
    
    for session in sessions:
        metrics['avgInferenceTime'] += session.get('avgInferenceTime', 0)
        metrics['minInferenceTime'] += session.get('minInferenceTime', 0)
        metrics['maxInferenceTime'] += session.get('maxInferenceTime', 0)
        metrics['p95InferenceTime'] += session.get('p95InferenceTime', 0)
        metrics['avgFps'] += session.get('avgFps', 0)
        metrics['totalInferences'] += session.get('totalInferences', 0)
        metrics['modelLoadTime'] += session.get('modelLoadTime', 0)
    
    # Calculate averages
    count = len(sessions)
    for key in metrics:
        if key != 'count':
            metrics[key] = metrics[key] / count
    
    return metrics

def format_time_us(microseconds):
    """Convert microseconds to milliseconds"""
    return microseconds / 1000.0

def print_comparison_table(device_data, sample_size=100):
    """Print comprehensive device comparison table"""
    
    if not device_data:
        print("No data available")
        return
    
    # Get all unique devices and delegates
    devices = sorted(device_data.keys())
    delegates = ['CPU', 'GPU', 'NPU_NNAPI']
    
    print("\n" + "="*150)
    print(f"DEVICE PERFORMANCE COMPARISON - Average of Last {sample_size} Recordings")
    print("="*150)
    print()
    
    # Calculate averages for all combinations
    device_averages = {}
    for device in devices:
        device_averages[device] = {}
        for delegate in delegates:
            sessions = device_data[device].get(delegate, [])
            if sessions:
                device_averages[device][delegate] = calculate_averages(sessions)
    
    # Print table for each metric
    metrics = [
        ('Avg Inference Time (ms)', 'avgInferenceTime', format_time_us),
        ('Min Inference Time (ms)', 'minInferenceTime', format_time_us),
        ('Max Inference Time (ms)', 'maxInferenceTime', format_time_us),
        ('P95 Inference Time (ms)', 'p95InferenceTime', format_time_us),
        ('Avg FPS', 'avgFps', lambda x: x),
        ('Avg Total Inferences', 'totalInferences', lambda x: x),
        ('Avg Model Load Time (ms)', 'modelLoadTime', lambda x: x),
        ('Sample Count', 'count', lambda x: x),
    ]
    
    for metric_name, metric_key, formatter in metrics:
        print(f"\n{metric_name}:")
        print("-" * 150)
        
        # Header
        header = f"{'Device':<25}"
        for delegate in delegates:
            header += f"{delegate:<25}"
        print(header)
        print("-" * 150)
        
        # Data rows
        for device in devices:
            row = f"{device:<25}"
            for delegate in delegates:
                avg_data = device_averages[device].get(delegate)
                if avg_data:
                    value = formatter(avg_data[metric_key])
                    if isinstance(value, float):
                        row += f"{value:<25.2f}"
                    else:
                        row += f"{value:<25}"
                else:
                    row += f"{'N/A':<25}"
            print(row)
    
    print("\n" + "="*150)
    
    # Device information
    print("\nDevice Information:")
    print("-" * 150)
    for device in devices:
        # Get device info from any available session
        device_info = None
        for delegate in delegates:
            sessions = device_data[device].get(delegate, [])
            if sessions:
                device_info = sessions[0]
                break
        
        if device_info:
            print(f"\n{device}:")
            print(f"  Name: {device_info.get('deviceName', 'N/A')}")
            print(f"  Manufacturer: {device_info.get('deviceManufacturer', 'N/A')}")
            print(f"  Android: {device_info.get('androidVersion', 'N/A')}")
            print(f"  Build: {device_info.get('buildNumber', 'N/A')}")
    
    print("\n" + "="*150)

def save_to_csv(device_data, filename="device_comparison.csv", sample_size=100):
    """Save comparison data to CSV file"""
    import csv
    from datetime import datetime
    
    devices = sorted(device_data.keys())
    delegates = ['CPU', 'GPU', 'NPU_NNAPI']
    
    # Calculate averages
    device_averages = {}
    for device in devices:
        device_averages[device] = {}
        for delegate in delegates:
            sessions = device_data[device].get(delegate, [])
            if sessions:
                device_averages[device][delegate] = calculate_averages(sessions)
    
    with open(filename, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        
        # Header
        writer.writerow(['Metric', 'Device'] + delegates)
        
        # Metrics
        metrics = [
            ('Avg Inference Time (ms)', 'avgInferenceTime', format_time_us),
            ('Min Inference Time (ms)', 'minInferenceTime', format_time_us),
            ('Max Inference Time (ms)', 'maxInferenceTime', format_time_us),
            ('P95 Inference Time (ms)', 'p95InferenceTime', format_time_us),
            ('Avg FPS', 'avgFps', lambda x: x),
            ('Avg Total Inferences', 'totalInferences', lambda x: x),
            ('Avg Model Load Time (ms)', 'modelLoadTime', lambda x: x),
            ('Sample Count', 'count', lambda x: x),
        ]
        
        for metric_name, metric_key, formatter in metrics:
            for device in devices:
                row = [metric_name, device]
                for delegate in delegates:
                    avg_data = device_averages[device].get(delegate)
                    if avg_data:
                        value = formatter(avg_data[metric_key])
                        row.append(f"{value:.2f}" if isinstance(value, float) else str(value))
                    else:
                        row.append("N/A")
                writer.writerow(row)
    
    print(f"\n✓ Data saved to {filename}")

def main():
    import sys
    
    # Parse arguments
    sample_size = 100
    if len(sys.argv) > 1:
        try:
            sample_size = int(sys.argv[1])
        except ValueError:
            print(f"Invalid sample size: {sys.argv[1]}, using default 100")
    
    print(f"Fetching data from Firebase (last {sample_size} recordings per device/delegate)...")
    data = fetch_firebase_data()
    
    if not data:
        print("❌ No data found in Firebase")
        return
    
    # Get last N sessions for each device/delegate
    device_data = get_last_n_sessions(data, sample_size)
    
    if not device_data:
        print("❌ No valid session data found")
        return
    
    # Print comparison table
    print_comparison_table(device_data, sample_size)
    
    # Save to CSV
    from datetime import datetime
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_filename = f"device_comparison_{timestamp}.csv"
    save_to_csv(device_data, csv_filename, sample_size)
    
    print(f"\n📊 Comparison complete!")
    print(f"   Devices analyzed: {len(device_data)}")
    print(f"   Sample size per delegate: {sample_size}")

if __name__ == "__main__":
    main()
