#include <stdlib.h>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <thread>
#include <iostream>
#include <vector>
#include <sstream>
#include <chrono>


using namespace std;

class SocketListener {
private:
    ConnectionHandler &handler;
    StompProtocol &protocol;
    bool &shouldTerminate;

public:
    SocketListener(ConnectionHandler &ch, StompProtocol &proto, bool &terminate) 
        : handler(ch), protocol(proto), shouldTerminate(terminate) {}

    void run() {
        while (!shouldTerminate) {
            string answer;
            if (!handler.getFrameAscii(answer, '\0')) {
                cout << "Disconnected from server." << endl;
                shouldTerminate = true;
                break;
            }

            if (answer.length() > 0) {
                cout << "<<< " << answer << endl;
                if (answer.find("MESSAGE") == 0) {
                    protocol.processMessage(answer);
                 }
            }


            if (protocol.shouldTerminate(answer)) {
                shouldTerminate = true;
                cout << "Logout successful. Press ENTER to finish." << endl;
            }
        }
    }
};

int main(int argc, char *argv[]) {
   
    while (true) {
        const short bufsize = 1024;
        char buf[bufsize];
        cin.getline(buf, bufsize);
        string line(buf);
        
        stringstream ss(line);
        string command;
        ss >> command;

        if (command == "login") {
            string hostPort, username, password;
            ss >> hostPort >> username >> password;
            
            size_t colonPos = hostPort.find(':');
            if (colonPos == string::npos) {
                cout << "Usage: login {host}:{port} {username} {password}" << endl;
                continue;
            }
            
            string host = hostPort.substr(0, colonPos);
            short port = stoi(hostPort.substr(colonPos + 1));
            
            ConnectionHandler connectionHandler(host, port);
            if (!connectionHandler.connect()) {
                cerr << "Cannot connect to " << host << ":" << port << endl;
                continue;
            }

           string frame = "CONNECT\n"
               "accept-version:1.2\n"
               "host:" + host + "\n"
               "login:" + username + "\n"
               "passcode:" + password + "\n\n";

            connectionHandler.sendFrameAscii(frame, '\0'); 

            StompProtocol protocol;
            bool shouldTerminate = false;
            
            SocketListener listener(connectionHandler, protocol, shouldTerminate);
            thread t(&SocketListener::run, &listener);

            while (!shouldTerminate) {
                char buf[bufsize];
                cin.getline(buf, bufsize);
                string userInput(buf);
                stringstream userSS(userInput);
                string userCmd;
                userSS >> userCmd;
                if (userCmd == "login") {
                        cout << "The client is already logged in, log out before trying again" << endl;
                        continue;
                }
                else if  (userCmd == "report") {
                    string fileName;
                    userSS >> fileName;
                    try {
                        names_and_events parsed = parseEventsFile(fileName);
                        
                        for (const Event& event : parsed.events) {
                            string reportFrame = protocol.createReportFrame(event, username);
                            connectionHandler.sendFrameAscii(reportFrame, '\0');
                        }
                    } catch (const exception& e) {
                        cout << "Error: " << e.what() << endl;
                    }
                }
                else if (userCmd == "summary") {
                    string gameName, user, fileName;
                    userSS >> gameName >> user >> fileName;
                    protocol.saveSummary(gameName, user, fileName); 
                    cout << "Summary report saved to " << fileName << endl;
                }  
                else if (userCmd == "logout") {
                    string frame = protocol.processInput(userInput, username);
                    connectionHandler.sendFrameAscii(frame, '\0'); 
                    while(!shouldTerminate) {
                         this_thread::sleep_for(std::chrono::milliseconds(10)); 
                    }
                } 
                else {
                    string frame = protocol.processInput(userInput, username);
                    if (!frame.empty()) {
                        connectionHandler.sendFrameAscii(frame, '\0');
                    }
                }
            }

            t.join();
            connectionHandler.close();

        } else {
            cout << "Please login first." << endl;
        }
    }
    return 0;
}