import socket
import sys

def test_login():
    host = 'localhost'
    port = 7778
    
    connect_frame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:meni\npasscode:films\n\n\x00"
    
    try:
        # 2. חיבור לשרת
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect((host, port))
        print(f"Connected to server on port {port}")
        
        sock.sendall(connect_frame.encode('utf-8'))
        print("Sent CONNECT frame...")
        
        data = sock.recv(1024)
        response = data.decode('utf-8')
        
        print("\n--- Server Response ---")
        print(response)
        print("-----------------------")
        
        if "CONNECTED" in response:
            print("✅ TEST PASSED: Login successful!")
        else:
            print("❌ TEST FAILED: Unexpected response.")
            
        sock.close()
        
    except ConnectionRefusedError:
        print("❌ FAILED: Could not connect. Is the server running?")
    except Exception as e:
        print(f"❌ ERROR: {e}")

if __name__ == "__main__":
    test_login()