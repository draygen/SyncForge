import psycopg2
import os

def cleanse_temple():
    print("--- Initiating VEDIC_PURGE via Python Gateway ---")
    try:
        conn = psycopg2.connect(
            dbname="mft_db",
            user=os.environ.get("DB_USER", "mft_user"),
            password=os.environ.get("DB_PASSWORD", "Renoise28!"),
            host="localhost",
            port="5432"
        )
        conn.autocommit = True
        cur = conn.cursor()
        
        # Truncate tables and reset IDs
        print("Purging metadata and karma logs...")
        cur.execute("TRUNCATE file_metadata, activity_events RESTART IDENTITY CASCADE;")
        
        print("✅ SUCCESS: Database layers sanitized.")
        cur.close()
        conn.close()
    except Exception as e:
        print(f"❌ FAILURE: Purge protocol interrupted: {e}")

if __name__ == "__main__":
    cleanse_temple()
