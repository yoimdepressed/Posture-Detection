#!/usr/bin/env python3
"""
Firebase Model Benchmarking Script - Device-Based
Compare performance metrics for slouching, cross-legged, and leaning models
across different devices from Firebase device_performance_data node.

Aggregates metrics across all devices for each model to determine overall
performance characteristics.

Metrics analyzed per model (aggregated across devices):
- Average inference time
- Max inference time
- Min inference time
- P95 inference time
- Average FPS
- Average model load time
- Number of devices tested

Usage:
    # Benchmark all devices
    python benchmark_models.py

    # Benchmark specific device
    python benchmark_models.py --device Samsung_SM_G991B

    # Fetch more records per device
    python benchmark_models.py --limit 200

    # Custom output directory
    python benchmark_models.py --output-dir ./results
"""

import json
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime
import requests
import argparse
from typing import Dict, List, Any

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
        node_path: Path to the data node in Firebase
        limit: Number of most recent records to fetch
        
    Returns:
        Dictionary containing device data
    """
    url = f"{FIREBASE_DATABASE_URL}/{node_path}/{device_id}.json"
    params = {
        "orderBy": '"$key"',
        "limitToLast": limit
    }
    
    print(f"  Fetching {limit} records for device: {device_id}")
    
    try:
        response = requests.get(url, params=params)
        response.raise_for_status()
        data = response.json()
        
        if data is None:
            print(f"  Warning: No data found for device {device_id}")
            return {}
        
        print(f"  → Fetched {len(data)} records")
        return data
    except requests.exceptions.RequestException as e:
        print(f"  Error fetching data for device {device_id}: {e}")
        return {}


def extract_model_metrics_by_device(all_devices_data: Dict[str, Dict[str, Any]]) -> pd.DataFrame:
    """
    Extract model-specific metrics from device-organized Firebase data.
    
    Args:
        all_devices_data: Dictionary mapping device_id -> device_data
        
    Returns:
        Pandas DataFrame with extracted metrics including device information
    """
    records = []
    
    for device_id, device_data in all_devices_data.items():
        if not isinstance(device_data, dict):
            continue
        
        for record_id, record_data in device_data.items():
            if not isinstance(record_data, dict):
                continue
                
            # Extract timestamp
            timestamp = record_data.get("timestamp", record_id)
            delegate = record_data.get("delegate", "Unknown")
            
            # Extract individual model metrics
            individual_models = record_data.get("individualModels", {})
            
            for model_name in MODELS:
                model_data = individual_models.get(model_name, {})
                
                if not model_data or not model_data.get("hasData", False):
                    continue
                
                record = {
                    "device_id": device_id,
                    "timestamp": timestamp,
                    "record_id": record_id,
                    "model": MODEL_DISPLAY_NAMES.get(model_name, model_name),
                    "delegate": delegate,
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


def generate_benchmark_report(df: pd.DataFrame) -> pd.DataFrame:
    """
    Generate comprehensive benchmark statistics for each model across all devices.
    Aggregates performance metrics from all devices for each model.
    
    Args:
        df: DataFrame with extracted metrics (includes device_id)
        
    Returns:
        DataFrame with benchmark statistics
    """
    if df.empty:
        print("No data available for benchmarking")
        return pd.DataFrame()
    
    # Group by model (aggregate across all devices)
    benchmark_data = []
    
    for model in df['model'].unique():
        model_df = df[df['model'] == model]
        
        stats = {
            'Model': model,
            'Devices Tested': model_df['device_id'].nunique(),
            'Total Samples': model_df['samples'].sum(),
            'Avg Inference Time (ms)': model_df['avg_inference_ms'].mean(),
            'Min Inference Time (ms)': model_df['min_inference_ms'].min(),
            'Max Inference Time (ms)': model_df['max_inference_ms'].max(),
            'P95 Inference Time (ms)': calculate_p95(model_df['avg_inference_ms'].values),
            'Avg FPS': model_df['avg_fps'].mean(),
            'Min FPS': model_df['avg_fps'].min(),
            'Max FPS': model_df['avg_fps'].max(),
            'Avg Total Time (ms)': model_df['avg_total_ms'].mean(),
            'Records Analyzed': len(model_df),
        }
        
        benchmark_data.append(stats)
    
    benchmark_df = pd.DataFrame(benchmark_data)
    return benchmark_df


def plot_benchmark_comparison(df: pd.DataFrame, output_dir: str = "."):
    """
    Create visualization plots for model comparison across all devices.
    
    Args:
        df: DataFrame with extracted metrics (includes device_id)
        output_dir: Directory to save plots
    """
    if df.empty:
        print("No data available for plotting")
        return
    
    # Set style
    sns.set_style("whitegrid")
    plt.rcParams['figure.figsize'] = (15, 10)
    
    num_devices = df['device_id'].nunique()
    fig, axes = plt.subplots(2, 3, figsize=(18, 12))
    fig.suptitle(f'Model Performance Comparison - {num_devices} Device(s)', fontsize=16, fontweight='bold')
    
    models = df['model'].unique()
    colors = sns.color_palette("husl", len(models))
    
    # 1. Average Inference Time
    ax = axes[0, 0]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model]
        ax.bar(i, model_df['avg_inference_ms'].mean(), color=colors[i], label=model)
    ax.set_xticks(range(len(models)))
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Average Inference Time')
    ax.grid(True, alpha=0.3)
    
    # 2. Min/Max Inference Time Range
    ax = axes[0, 1]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model]
        min_val = model_df['min_inference_ms'].min()
        max_val = model_df['max_inference_ms'].max()
        avg_val = model_df['avg_inference_ms'].mean()
        ax.plot([i, i], [min_val, max_val], 'o-', color=colors[i], linewidth=2, markersize=8)
        ax.plot(i, avg_val, 's', color=colors[i], markersize=10, label=f'{model} (avg)')
    ax.set_xticks(range(len(models)))
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Inference Time Range (Min-Max)')
    ax.grid(True, alpha=0.3)
    ax.legend()
    
    # 3. P95 Inference Time
    ax = axes[0, 2]
    p95_values = []
    for i, model in enumerate(models):
        model_df = df[df['model'] == model]
        p95 = calculate_p95(model_df['avg_inference_ms'].values)
        p95_values.append(p95)
        ax.bar(i, p95, color=colors[i], label=model)
    ax.set_xticks(range(len(models)))
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('P95 Inference Time')
    ax.grid(True, alpha=0.3)
    
    # 4. Average FPS
    ax = axes[1, 0]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model]
        ax.bar(i, model_df['avg_fps'].mean(), color=colors[i], label=model)
    ax.set_xticks(range(len(models)))
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('FPS')
    ax.set_title('Average Frames Per Second')
    ax.grid(True, alpha=0.3)
    
    # 5. Inference Time Distribution (Box Plot)
    ax = axes[1, 1]
    data_for_box = [df[df['model'] == model]['avg_inference_ms'].values for model in models]
    bp = ax.boxplot(data_for_box, labels=models, patch_artist=True)
    for patch, color in zip(bp['boxes'], colors):
        patch.set_facecolor(color)
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Inference Time Distribution')
    ax.grid(True, alpha=0.3)
    
    # 6. Total Processing Time
    ax = axes[1, 2]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model]
        ax.bar(i, model_df['avg_total_ms'].mean(), color=colors[i], label=model)
    ax.set_xticks(range(len(models)))
    ax.set_xticklabels(models, rotation=15, ha='right')
    ax.set_ylabel('Time (ms)')
    ax.set_title('Average Total Processing Time')
    ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    output_path = f"{output_dir}/model_benchmark_comparison.png"
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Saved comparison plot to: {output_path}")
    plt.close()
    
    # Create time series plot
    fig, axes = plt.subplots(2, 1, figsize=(15, 10))
    fig.suptitle('Model Performance Over Time', fontsize=16, fontweight='bold')
    
    # Inference time over records
    ax = axes[0]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model].reset_index(drop=True)
        ax.plot(model_df.index, model_df['avg_inference_ms'], 
                marker='o', label=model, color=colors[i], alpha=0.7)
    ax.set_xlabel('Record Index')
    ax.set_ylabel('Inference Time (ms)')
    ax.set_title('Inference Time Across Records')
    ax.legend()
    ax.grid(True, alpha=0.3)
    
    # FPS over records
    ax = axes[1]
    for i, model in enumerate(models):
        model_df = df[df['model'] == model].reset_index(drop=True)
        ax.plot(model_df.index, model_df['avg_fps'], 
                marker='s', label=model, color=colors[i], alpha=0.7)
    ax.set_xlabel('Record Index')
    ax.set_ylabel('FPS')
    ax.set_title('FPS Across Records')
    ax.legend()
    ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    output_path = f"{output_dir}/model_performance_timeline.png"
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Saved timeline plot to: {output_path}")
    plt.close()


def save_results(benchmark_df: pd.DataFrame, raw_df: pd.DataFrame, output_dir: str = "."):
    """
    Save benchmark results to CSV and JSON files.
    
    Args:
        benchmark_df: DataFrame with benchmark statistics
        raw_df: DataFrame with raw extracted data
        output_dir: Directory to save results
    """
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # Save benchmark summary
    csv_path = f"{output_dir}/benchmark_summary_{timestamp}.csv"
    benchmark_df.to_csv(csv_path, index=False)
    print(f"Saved benchmark summary to: {csv_path}")
    
    # Save raw data
    raw_csv_path = f"{output_dir}/raw_metrics_{timestamp}.csv"
    raw_df.to_csv(raw_csv_path, index=False)
    print(f"Saved raw metrics to: {raw_csv_path}")
    
    # Save as JSON
    json_path = f"{output_dir}/benchmark_summary_{timestamp}.json"
    benchmark_dict = benchmark_df.to_dict(orient='records')
    with open(json_path, 'w') as f:
        json.dump(benchmark_dict, f, indent=2)
    print(f"Saved benchmark summary to: {json_path}")


def print_benchmark_table(benchmark_df: pd.DataFrame):
    """Print benchmark results as a formatted table."""
    if benchmark_df.empty:
        print("No benchmark data to display")
        return
    
    print("\n" + "="*120)
    print(" " * 40 + "MODEL BENCHMARK COMPARISON")
    print("="*120)
    print(benchmark_df.to_string(index=False))
    print("="*120)
    
    # Print winner for each metric
    print("\n" + "="*120)
    print(" " * 45 + "PERFORMANCE WINNERS")
    print("="*120)
    
    metrics = {
        'Fastest Avg Inference': ('Avg Inference Time (ms)', 'min'),
        'Fastest Min Inference': ('Min Inference Time (ms)', 'min'),
        'Fastest Max Inference': ('Max Inference Time (ms)', 'min'),
        'Best P95 Inference': ('P95 Inference Time (ms)', 'min'),
        'Highest Avg FPS': ('Avg FPS', 'max'),
        'Fastest Total Processing': ('Avg Total Time (ms)', 'min'),
    }
    
    for metric_name, (column, agg_func) in metrics.items():
        if agg_func == 'min':
            winner_idx = benchmark_df[column].idxmin()
        else:
            winner_idx = benchmark_df[column].idxmax()
        
        winner_model = benchmark_df.loc[winner_idx, 'Model']
        winner_value = benchmark_df.loc[winner_idx, column]
        
        print(f"{metric_name:30s}: {winner_model:20s} ({winner_value:.2f})")
    
    print("="*120 + "\n")


def main():
    parser = argparse.ArgumentParser(
        description="Benchmark posture detection models from Firebase device-based data"
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
        "--device", 
        type=str, 
        default=None, 
        help="Specific device ID to benchmark (default: all devices)"
    )
    
    args = parser.parse_args()
    
    print("="*120)
    print(" " * 35 + "FIREBASE MODEL BENCHMARKING TOOL")
    print("="*120)
    print(f"Firebase Project: {FIREBASE_PROJECT_ID}")
    print(f"Database URL: {FIREBASE_DATABASE_URL}")
    print(f"Fetching from '{args.node}' node")
    if args.device:
        print(f"Benchmarking specific device: {args.device}")
    else:
        print(f"Benchmarking all devices (up to {args.limit} records per device)")
    print("="*120 + "\n")
    
    # Fetch data from Firebase
    all_devices_data = {}
    
    if args.device:
        # Fetch specific device
        device_data = fetch_device_data(args.device, args.node, args.limit)
        if device_data:
            all_devices_data[args.device] = device_data
    else:
        # Fetch all devices
        devices = fetch_all_devices(args.node)
        
        if not devices:
            print("No devices found in Firebase. Exiting.")
            return
        
        print(f"\nFetching data for {len(devices)} device(s)...")
        for device_id in devices:
            device_data = fetch_device_data(device_id, args.node, args.limit)
            if device_data:
                all_devices_data[device_id] = device_data
    
    if not all_devices_data:
        print("No data retrieved from Firebase. Exiting.")
        return
    
    total_records = sum(len(data) for data in all_devices_data.values())
    print(f"\nTotal records fetched: {total_records} across {len(all_devices_data)} device(s)")
    
    # Extract metrics
    print("\nExtracting model metrics...")
    df = extract_model_metrics_by_device(all_devices_data)
    
    if df.empty:
        print("No valid model metrics found in the data. Exiting.")
        return
    
    print(f"Extracted metrics for {len(df)} model instances")
    print(f"Devices: {', '.join(df['device_id'].unique())}")
    print(f"Models found: {', '.join(df['model'].unique())}")
    
    # Generate benchmark report
    print("\nCalculating benchmark statistics...")
    benchmark_df = generate_benchmark_report(df)
    
    # Print results
    print_benchmark_table(benchmark_df)
    
    # Save results
    print("\nSaving results...")
    save_results(benchmark_df, df, args.output_dir)
    
    # Generate plots
    if not args.no_plots:
        print("\nGenerating visualization plots...")
        try:
            plot_benchmark_comparison(df, args.output_dir)
        except Exception as e:
            print(f"Error generating plots: {e}")
    
    print("\n" + "="*120)
    print(" " * 45 + "BENCHMARKING COMPLETE")
    print("="*120)


if __name__ == "__main__":
    main()
