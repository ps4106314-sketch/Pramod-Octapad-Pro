import json
import random
import string
import argparse

def generate_key(prefix="PRAMOD", length=8):
    # Generate a random string of uppercase letters and digits
    characters = string.ascii_uppercase + string.digits
    random_str = ''.join(random.choice(characters) for _ in range(length))
    return f"{prefix}-{random_str}"

def generate_keys_json(num_keys, prefix="PRAMOD", length=8, output_file="firebase_keys.json"):
    keys_data = {"activation_keys": {}}
    
    for _ in range(num_keys):
        key = generate_key(prefix, length)
        keys_data["activation_keys"][key] = {
            "status": "unused",
            "device_id": ""
        }
        
    with open(output_file, 'w') as f:
        json.dump(keys_data, f, indent=2)
        
    print(f"Successfully generated {num_keys} keys and saved to {output_file}")
    print("You can now import this file directly into your Firebase Realtime Database.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate Activation Keys for Octapad Pro")
    parser.add_argument("--count", type=int, default=50, help="Number of keys to generate (default: 50)")
    parser.add_argument("--prefix", type=str, default="PRAMOD", help="Prefix for the keys (default: PRAMOD)")
    parser.add_argument("--output", type=str, default="firebase_keys.json", help="Output JSON file name")
    
    args = parser.parse_args()
    
    generate_keys_json(args.count, args.prefix, 8, args.output)
