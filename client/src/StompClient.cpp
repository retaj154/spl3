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

static bool extractHeaderValue(const string &frame, const string &headerKey, string &outValue) {
    istringstream iss(frame);
    string line;
    // skip command line
    if (!getline(iss, line)) return false;
    while (getline(iss, line)) {
        if (line == "\r") line = "";
        if (line.empty()) break;
        if (line.rfind(headerKey + ":", 0) == 0) {
            outValue = line.substr(headerKey.size() + 1);
            // trim spaces
            size_t b = outValue.find_first_not_of(" \t\r");
            size_t e = outValue.find_last_not_of(" \t\r");
            if (b == string::npos) outValue = "";
            else outValue = outValue.substr(b, e - b + 1);
            return true;
        }
    }
    return false;
}

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
            string frame;
            if (!handler.getFrameAscii(frame, '\0')) {
                cout << "Disconnected from server." << endl;
                shouldTerminate = true;
                break;
            }

            if (frame.rfind("CONNECTED", 0) == 0) {
                cout << "Login successful" << endl;
                continue;
            }

            if (frame.rfind("ERROR", 0) == 0) {
                string msg;
                if (!extractHeaderValue(frame, "message", msg)) {
                    msg = "ERROR";
                }
                cout << msg << endl;
                shouldTerminate = true;
                break;
            }

            if (frame.rfind("RECEIPT", 0) == 0) {
                string rid;
                if (extractHeaderValue(frame, "receipt-id", rid)) {
                    int receiptId = -1;
                    try { receiptId = stoi(rid); } catch (...) { receiptId = -1; }

                    string toPrint = protocol.onReceipt(receiptId);
                    if (!toPrint.empty()) {
                        cout << toPrint << endl;
                    }

                    if (protocol.isLogoutReceipt(receiptId)) {
                        shouldTerminate = true;
                        break;
                    }
                }
                continue;
            }

            if (frame.rfind("MESSAGE", 0) == 0) {
                protocol.processMessage(frame);
                continue;
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

        if (command != "login") {
            cout << "Please login first." << endl;
            continue;
        }

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
            cout << "Could not connect to server" << endl;
            continue;
        }

        string connectFrame = "CONNECT\n"
                              "accept-version:1.2\n"
                              "host:" + host + "\n"
                              "login:" + username + "\n"
                              "passcode:" + password + "\n\n";

        connectionHandler.sendFrameAscii(connectFrame, '\0');

        StompProtocol protocol;
        bool shouldTerminate = false;

        SocketListener listener(connectionHandler, protocol, shouldTerminate);
        thread t(&SocketListener::run, &listener);

        bool waitingForLogout = false;

        while (!shouldTerminate) {
            if (waitingForLogout) {
                this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            char buf2[bufsize];
            cin.getline(buf2, bufsize);
            string userInput(buf2);

            stringstream userSS(userInput);
            string userCmd;
            userSS >> userCmd;

            if (userCmd == "login") {
                cout << "The client is already logged in, log out before trying again" << endl;
                continue;
            }

            if (userCmd == "report") {
                string fileName;
                userSS >> fileName;
                try {
                    names_and_events parsed = parseEventsFile(fileName);

                    bool first = true;
                    for (const Event &event: parsed.events) {
                        string headerFile = first ? fileName : "";
                        first = false;

                        // Spec: save every event the client sends, for summary (even for current user)
                        protocol.saveSentEvent(event, username);

                        string reportFrame = protocol.createReportFrame(event, username, headerFile);
                        connectionHandler.sendFrameAscii(reportFrame, '\0');
                    }
                } catch (const exception &e) {
                    cout << "Error: " << e.what() << endl;
                }
                continue;
            }

            if (userCmd == "summary") {
                string gameName, user, fileName;
                userSS >> gameName >> user >> fileName;
                protocol.saveSummary(gameName, user, fileName);
                continue;
            }

            string outFrame = protocol.processInput(userInput, username);
            if (!outFrame.empty()) {
                connectionHandler.sendFrameAscii(outFrame, '\0');
            }

            if (userCmd == "logout") {
                waitingForLogout = true;
            }
        }

        t.join();
        connectionHandler.close();
    }

    return 0;
}
