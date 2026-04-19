#!/usr/bin/env python3
"""
Device Performance Comparison Script
Compare model performance (slouching, cross-legged, leaning) across different devices
using data collected from Firebase.

Metrics compared per device:
- Average inference time
- Max inference time
- Min inference time
- P95 inference time
- Average FPS
- Total processing time
"""

import json
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime
import requests
import argparse
from typing import Dict, List, Any, Tuple
from collections import defaultdict

# Firebase configuration
FIREBASE_PROJECT_ID = "postureanalyzer-b24a3"
FIREBASE_DATABASE_URL = f"https://{FIREBASE_PROJECT_ID}-default-rtdb.asia-southeast1.firebasedatabase.app"
FIREBASE_API_KEY = "AIzaSyBs4wxCUEYwMDhBLgpeASu1DSwWfIujkm8"

# Model names
MODELS = ["slouchModel", "crossLeggedModel", "leanModel"]
MODEL_DISPLAY_NAMES = {
    "slouchModel": "Slouching",
    "crossLeggedModel": "Cross-Legged",
    "leanModel": "Leaning"
}


def fetch_all_devices(node_path: str = "device_performance_data") -> List[str]:
    """
    Fetch list of all device IDs from Firebase.
    
    Args:
        node_path: Path to the device data node in Firebase
        
    Returns:
        List of device IDs
    """
    url = f"{FIREBASE_DATABASE_URL}/{node_path}.json"
    params = {"shallow": "true"}
    
    print(f"Fetching device list from Firebase: {node_path}")
    
    try:
        response = requests.get(url, params=params)
        response.raise_for_status()
        data = response.json()
        
        if data is None:
            print(f"Warning: No devices found at {node_path}")
            return []
        
        devices = list(data.keys())
        print(f"Found {len(devices)} device(s): {', '.join(devices)}")
        return devices
    except requests.exceptions.RequestException as e:
        print(f"Error fetching device list from Firebase: {e}")
        return []


def fetch_device_data(device_id: str, node_path: str = "device_performance_data", limit: int = 100) -> Dict[str, Any]:
    """
    Fetch data for a specific device from Firebase.
    
    Args:
        device_id: Device identifier
        node_path: Path to the device data node in Firebase
        limit: Number of most recent records to fetch
        
    Returns:
        Dictionary containing device data
    """
    url = f"{FIREBASE_DATABASE_URL}/{node_path}/{device_id}.json"
    params = {
        "orderBy": '"$key"',
        "limitToLast": limit
    }
    
    print(f"Fetching data for device: {device_id}")
    
    try:
        response = requests.get(url, params=params)
        response.raise_for_status()
        data = response.json()
        
        if data is None:
            print(f"Warning: No data found for device {device_id}")
            return {}
        
        print(f"  → Fetched {len(data)} records")
        return data
    except requests.exceptions.RequestException as e:
        print(f"Error fetching data for device {device_id}: {e}")
        return {}


def extract_device_info(device_data: Dict[str, Any]) -> Dict[str, str]:
    """Extract device metadata from first record."""
    for record_id, record_data in device_data.items():
        if isinstance(record_data, dict):
            return {
                "manufacturer": record_data.get("deviceManufacturer", "Unknown"),
                "model": record_data.get("deviceModel", "Unknown"),
                "android_version": record_data.get("androidVersion", "Unknown"),
                "device_id": record_data.get("deviceId", "Unknown")
            }
    return {"manufacturer": "Unknown", "model": "Unknown", "android_version": "Unknown", "device_id": "Unknown"}


def extract_model_metrics_by_device(all_device_data: Dict[str, Dict[str, Any]]) -> pd.DataFrame:
    """
    Extract model-specific metrics from all devices.
    
    Args:
        all_device_data: Dictionary mapping device_id to their Firebase data
        
    Returns:
        Pandas DataFrame with extracted metrics for all devices
    """
    records = []
    
    for device_id, device_data in all_device_data.items():
        device_info = extract_device_info(device_data)
        device_display_name = f"{device_info['manufacturer']} {device_info['model']}"
        
        for record_id, record_data in device_data.items():
            if not isinstance(record_data, dict):
                continue
            
            # Extract timestamp
            timestamp = record_data.get("timestamp", record_id)
            delegate = record_data.get("delegate", "Unknown")
            runtime = record_data.get("runtime", "Unknown")
            camera_type = record_data.get("cameraType", "Unknown")
            
            # Extract individual model metrics
            individual_models = record_data.get("individualModels", {})
            
            for model_name in MODELS:
                model_data = individual_models.get(model_name, {})
                
                if not model_data or not model_data.get("hasData", False):
                    continue
                
                record = {
                    "device_id": device_id,
                    "device_name": device_display_name,
                    "manufacturer": device_info["manufacturer"],
                    "model_name_device": device_info["model"],
                    "android_version": device_info["android_version"],
                    "timestamp": timestamp,
                    "record_id": record_id,
                    "model": MODEL_DISPLAY_NAMES.get(model_name, model_name),
                    "delegate": delegate,
                    "runtime": runtime,
                    "camera_type": camera_type,
                    "samples": model_data.get("samples", 0),
                    "avg_inference_us": model_data.get("avgInferenceUs", 0),
                    "min_inference_us": model_data.get("minInferenceUs", 0),
                    "max_inference_us": model_data.get("maxInferenceUs", 0),
                    "avg_total_us": model_data.get("avgTotalUs", 0),
                    "min_total_us": model_data.get("minTotalUs", 0),
                    "max_total_us": model_data.get("maxTotalUs", 0),
                    "avg_fps": model_data.get("avgFps", 0.0),
                }
                
                records.append(record)
    
    df = pd.DataFrame(records)
    
    if not df.empty:
        # Convert microseconds to milliseconds for better readability
        time_columns = [col for col in df.columns if '_us' in col]
        for col in time_columns:
            ms_col = col.replace('_us', '_ms')
            df[ms_col] = df[col] / 1000.0
    
    return df


def calculate_p95(values: np.ndarray) -> float:
    """Calculate 95th percentile."""
    if len(values) == 0:
        return 0.0
    return np.percentile(values, 95)


def generate_device_comparison_report(df: pd.DataFrame) -> pd.DataFrame:
    """
    Generate comprehensive comparison statistics across devices for each model.
    
    Args:
        df: DataFrame with extracted metrics
        
    Returns:
        DataFrame with device comparison statistics
    """
    if df.empty:
        print("No data available for comparison")
        return pd.DataFrame()
    
    # Group by device and model
    comparison_data = []
    
    for device in df['device_name'].unique():
        for model in MODELS:
            model_display = MODEL_DISPLAY_NAMES[model]
            device_model_df = df[(df['device_name'] == device) & (df['model'] == model_display)]
            
            if device_model_df.empty:
                continue
            
            stats = {
                'Device': device,
                'Model': model_display,
                'Total Samples': device_model_df['samples'].sum(),
                'Avg Inference (ms)': device_model_df['avg_inference_ms'].mean(),
                'Min Inference (ms)': device_model_df['min_inference_ms'].min(),
                'Max Inference (ms)': device_model_df['max_inference_ms'].max(),
                'P95 Inference (ms)': calculate_p95(device_model_df['avg_inference_ms'].values),
                'Avg FPS': device_model_df['avg_fps'].mean(),
                'Avg Total Time (ms)': device_model_df['avg_total_ms'].mean(),
                'Records': len(device_model_df),
                'Android Version': device_model_df['android_version'].iloc[0],
                'Delegate': device_model_df['delegate'].mode()[0] if not device_model_df['delegate'].mode().empty else "Unknown",
            }
            
            comparison_data.append(stats)
    
    comparison_df = pd.DataFrame(comparison_data)
    return comparison_df


def plot_device_comparison(df: pd.DataFrame, output_dir: str = "."):
    """
    Create comprehensive visualization plots for device comparison.
    
    Args:
        df: DataFrame with extracted metrics
        output_dir: Directory to save plots
    """
    if df.empty:
        print("No data available for plotting")
        return
    
    # Set style
    sns.set_style("whitegrid")
    
    devices = df['device_name'].unique()
    models = df['model'].unique()
    n_devices = len(devices)
    n_models = len(models)
    
    device_colors = sns.color_palette("Set2", n_devices)
    model_colors = sns.color_palette("husl", n_models)
    
    # ==================== Plot 1: Device Comparison for Each Model ====================
    fig, axes = plt.subplots(2, 3, figsize=(20, 12))
    fig.suptitle('Device Performance Comparison Across Models', fontsize=16, fontweight='bold')
    
    # 1. Average Inference Time by Model (Grouped by Device)
    ax = axes[0, 0]
    x = np.arange(n_models)
    width = 0.8 / n_devices
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            avg_val = model_df['avg_inference_ms'].mean() if not model_df.empty else 0
            device_data.append(avg_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Avg Inference Time by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    # 2. Max Inference Time Comparison
    ax = axes[0, 1]
    x = np.arange(n_models)
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            max_val = model_df['max_inference_ms'].max() if not model_df.empty else 0
            device_data.append(max_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Max Inference Time by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    # 3. P95 Inference Time Comparison
    ax = axes[0, 2]
    x = np.arange(n_models)
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            p95_val = calculate_p95(model_df['avg_inference_ms'].values) if not model_df.empty else 0
            device_data.append(p95_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('P95 Inference Time by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    # 4. Average FPS by Model
    ax = axes[1, 0]
    x = np.arange(n_models)
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            fps_val = model_df['avg_fps'].mean() if not model_df.empty else 0
            device_data.append(fps_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('FPS')
    ax.set_title('Avg FPS by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    # 5. Total Processing Time
    ax = axes[1, 1]
    x = np.arange(n_models)
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            total_val = model_df['avg_total_ms'].mean() if not model_df.empty else 0
            device_data.append(total_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Avg Total Processing Time by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    # 6. Min Inference Time
    ax = axes[1, 2]
    x = np.arange(n_models)
    for i, device in enumerate(devices):
        device_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            min_val = model_df['min_inference_ms'].min() if not model_df.empty else 0
            device_data.append(min_val)
        ax.bar(x + i * width, device_data, width, label=device, color=device_colors[i])
    ax.set_xticks(x + width * (n_devices - 1) / 2)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Min Inference Time by Model')
    ax.legend(loc='upper left', fontsize=8)
    ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    output_path = f"{output_dir}/device_comparison_by_model.png"
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Saved device comparison plot to: {output_path}")
    plt.close()
    
    # ==================== Plot 2: Model Comparison for Each Device ====================
    fig, axes = plt.subplots(n_devices, 2, figsize=(16, 6 * n_devices))
    if n_devices == 1:
        axes = axes.reshape(1, -1)
    fig.suptitle('Model Performance Comparison per Device', fontsize=16, fontweight='bold')
    
    for idx, device in enumerate(devices):
        device_df = df[df['device_name'] == device]
        
        # Inference Time Comparison
        ax = axes[idx, 0]
        x = np.arange(n_models)
        avg_vals = []
        min_vals = []
        max_vals = []
        for model in models:
            model_df = device_df[device_df['model'] == model]
            if not model_df.empty:
                avg_vals.append(model_df['avg_inference_ms'].mean())
                min_vals.append(model_df['min_inference_ms'].min())
                max_vals.append(model_df['max_inference_ms'].max())
            else:
                avg_vals.append(0)
                min_vals.append(0)
                max_vals.append(0)
        
        ax.bar(x - 0.2, avg_vals, 0.2, label='Avg', color=model_colors[0])
        ax.bar(x, min_vals, 0.2, label='Min', color=model_colors[1])
        ax.bar(x + 0.2, max_vals, 0.2, label='Max', color=model_colors[2])
        ax.set_xticks(x)
        ax.set_xticklabels(models, rotation=15, ha='right')
        ax.set_ylabel('Time (ms)')
        ax.set_title(f'{device} - Inference Times')
        ax.legend()
        ax.grid(True, alpha=0.3)
        
        # FPS Comparison
        ax = axes[idx, 1]
        fps_vals = []
        for model in models:
            model_df = device_df[device_df['model'] == model]
            fps_vals.append(model_df['avg_fps'].mean() if not model_df.empty else 0)
        
        ax.bar(x, fps_vals, color=model_colors[:n_models])
        ax.set_xticks(x)
        ax.set_xticklabels(models, rotation=15, ha='right')
        ax.set_ylabel('FPS')
        ax.set_title(f'{device} - Average FPS')
        ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    output_path = f"{output_dir}/model_comparison_by_device.png"
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Saved model comparison plot to: {output_path}")
    plt.close()
    
    # ==================== Plot 3: Heatmap of Average Inference Times ====================
    fig, ax = plt.subplots(figsize=(12, 8))
    
    # Create pivot table for heatmap
    heatmap_data = []
    for device in devices:
        row_data = []
        for model in models:
            model_df = df[(df['device_name'] == device) & (df['model'] == model)]
            avg_val = model_df['avg_inference_ms'].mean() if not model_df.empty else 0
            row_data.append(avg_val)
        heatmap_data.append(row_data)
    
    sns.heatmap(heatmap_data, annot=True, fmt='.2f', cmap='YlOrRd', 
                xticklabels=models, yticklabels=devices, ax=ax, cbar_kws={'label': 'Avg Inference Time (ms)'})
    ax.set_title('Average Inference Time Heatmap: Device vs Model', fontsize=14, fontweight='bold')
    ax.set_xlabel('Model', fontsize=12)
    ax.set_ylabel('Device', fontsize=12)
    
    plt.tight_layout()
    output_path = f"{output_dir}/inference_time_heatmap.png"
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Saved heatmap to: {output_path}")
    plt.close()


def save_results(comparison_df: pd.DataFrame, raw_df: pd.DataFrame, output_dir: str = "."):
    """
    Save comparison results to CSV and JSON files.
    
    Args:
        comparison_df: DataFrame with comparison statistics
        raw_df: DataFrame with raw extracted data
        output_dir: Directory to save results
    """
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # Save comparison summary
    csv_path = f"{output_dir}/device_comparison_summary_{timestamp}.csv"
    comparison_df.to_csv(csv_path, index=False)
    print(f"Saved comparison summary to: {csv_path}")
    
    # Save raw data
    raw_csv_path = f"{output_dir}/device_raw_metrics_{timestamp}.csv"
    raw_df.to_csv(raw_csv_path, index=False)
    print(f"Saved raw metrics to: {raw_csv_path}")
    
    # Save as JSON
    json_path = f"{output_dir}/device_comparison_summary_{timestamp}.json"
    comparison_dict = comparison_df.to_dict(orient='records')
    with open(json_path, 'w') as f:
        json.dump(comparison_dict, f, indent=2)
    print(f"Saved comparison summary to: {json_path}")


def print_comparison_table(comparison_df: pd.DataFrame):
    """Print comparison results as formatted tables."""
    if comparison_df.empty:
        print("No comparison data to display")
        return
    
    devices = comparison_df['Device'].unique()
    
    for device in devices:
        device_df = comparison_df[comparison_df['Device'] == device]
        
        print("\n" + "="*140)
        print(f"{' ' * 50}{device}")
        print("="*140)
        
        # Prepare display columns
        display_df = device_df[['Model', 'Avg Inference (ms)', 'Min Inference (ms)', 
                                 'Max Inference (ms)', 'P95 Inference (ms)', 
                                 'Avg FPS', 'Total Samples', 'Records']]
        print(display_df.to_string(index=False))
        print("="*140)
    
    # Overall comparison across devices
    print("\n" + "="*140)
    print(" " * 55 + "DEVICE PERFORMANCE WINNERS")
    print("="*140)
    
    for model in comparison_df['Model'].unique():
        model_df = comparison_df[comparison_df['Model'] == model]
        
        print(f"\n{model}:")
        print("-" * 140)
        
        # Fastest average inference
        fastest_idx = model_df['Avg Inference (ms)'].idxmin()
        fastest_device = model_df.loc[fastest_idx, 'Device']
        fastest_time = model_df.loc[fastest_idx, 'Avg Inference (ms)']
        print(f"  Fastest Avg Inference   : {fastest_device:40s} ({fastest_time:.2f} ms)")
        
        # Best P95
        best_p95_idx = model_df['P95 Inference (ms)'].idxmin()
        best_p95_device = model_df.loc[best_p95_idx, 'Device']
        best_p95_time = model_df.loc[best_p95_idx, 'P95 Inference (ms)']
        print(f"  Best P95 Inference      : {best_p95_device:40s} ({best_p95_time:.2f} ms)")
        
        # Highest FPS
        best_fps_idx = model_df['Avg FPS'].idxmax()
        best_fps_device = model_df.loc[best_fps_idx, 'Device']
        best_fps = model_df.loc[best_fps_idx, 'Avg FPS']
        print(f"  Highest FPS             : {best_fps_device:40s} ({best_fps:.2f} fps)")
    
    print("="*140 + "\n")


def main():
    parser = argparse.ArgumentParser(
        description="Compare model performance across different devices using Firebase data"
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=1000,
        help="Number of most recent records to fetch per device (default: 1000)"
    )
    parser.add_argument(
        "--node", 
        type=str, 
        default="device_performance_data", 
        help="Firebase node path (default: device_performance_data)"
    )
    parser.add_argument(
        "--output-dir", 
        type=str, 
        default=".", 
        help="Output directory for results (default: current directory)"
    )
    parser.add_argument(
        "--no-plots", 
        action="store_true", 
        help="Skip generating plots"
    )
    parser.add_argument(
        "--devices",
        type=str,
        nargs='+',
        help="Specific device IDs to compare (default: all devices)"
    )
    
    args = parser.parse_args()
    
    print("="*140)
    print(" " * 50 + "DEVICE PERFORMANCE COMPARISON TOOL")
    print("="*140)
    print(f"Firebase Project: {FIREBASE_PROJECT_ID}")
    print(f"Database URL: {FIREBASE_DATABASE_URL}")
    print(f"Fetching last {args.limit} records per device from '{args.node}' node")
    print("="*140 + "\n")
    
    # Get list of devices
    if args.devices:
        device_ids = args.devices
        print(f"Comparing specified devices: {', '.join(device_ids)}\n")
    else:
        device_ids = fetch_all_devices(args.node)
        if not device_ids:
            print("No devices found in Firebase. Exiting.")
            return
    
    # Fetch data for all devices
    all_device_data = {}
    print(f"\nFetching data for {len(device_ids)} device(s)...")
    for device_id in device_ids:
        device_data = fetch_device_data(device_id, args.node, args.limit)
        if device_data:
            all_device_data[device_id] = device_data
    
    if not all_device_data:
        print("No data retrieved from Firebase for any device. Exiting.")
        return
    
    print(f"\nSuccessfully fetched data for {len(all_device_data)} device(s)")
    
    # Extract metrics
    print("\nExtracting model metrics from all devices...")
    df = extract_model_metrics_by_device(all_device_data)
    
    if df.empty:
        print("No valid model metrics found in the data. Exiting.")
        return
    
    print(f"Extracted metrics for {len(df)} model instances across {len(df['device_name'].unique())} devices")
    print(f"Devices: {', '.join(df['device_name'].unique())}")
    print(f"Models: {', '.join(df['model'].unique())}")
    
    # Generate comparison report
    print("\nCalculating comparison statistics...")
    comparison_df = generate_device_comparison_report(df)
    
    # Print results
    print_comparison_table(comparison_df)
    
    # Save results
    print("\nSaving results...")
    save_results(comparison_df, df, args.output_dir)
    
    # Generate plots
    if not args.no_plots:
        print("\nGenerating visualization plots...")
        try:
            plot_device_comparison(df, args.output_dir)
        except Exception as e:
            print(f"Error generating plots: {e}")
            import traceback
            traceback.print_exc()
    
    print("\n" + "="*140)
    print(" " * 55 + "COMPARISON COMPLETE")
    print("="*140)


if __name__ == "__main__":
    main()
