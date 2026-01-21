#pragma once

#include "../include/ConnectionHandler.h"
#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <fstream>
#include "event.h"

using namespace std;

// Implements the client-side logic required by the assignment (join/exit/report/summary/logout).
class StompProtocol {
private:
    int subscriptionIdCounter;
    int receiptIdCounter;

    // destination (with leading '/') -> subscription-id
    map<string, int> topicToSubId;

    // game -> user -> list of events received
    map<string, map<string, vector<Event>>> game_reports;

    // receipt-id -> message to print when RECEIPT arrives
    map<int, string> receiptToPrint;

    int logoutReceiptId;

    static string trim(const string &s);
    static bool startsWith(const string &s, const string &prefix);
    static bool parseKeyValue(const string &line, string &key, string &val);

    // Ordering helper: handle the "before halftime" edge-case described in the PDF.
    // phase: 0 = before halftime, 1 = after halftime, 2 = unknown
    static int halftimePhase(const Event &e);
    static bool eventLess(const Event &a, const Event &b);

public:
    StompProtocol();

    // Convert a keyboard command to a STOMP frame.
    string processInput(const string &line, const string &username);

    // Handle MESSAGE frames: parse event body and store it.
    void processMessage(const string &frame);

    // Create a SEND frame for a game event. Optionally add a "file" header once (for file tracking on server).
    string createReportFrame(const Event &event, const string &username, const string &fileNameHeader);

    // Save summary to file (as required).
    void saveSummary(const string &gameName, const string &user, const string &filePath);

    // Required by spec: the client should save every game event it sends (report command)
    // so that "summary" can work even for the current user.
    void saveSentEvent(const Event &event, const string &username);

    // Called when a RECEIPT arrives; returns a user-friendly line to print (or empty).
    string onReceipt(int receiptId);

    // True if this receipt id corresponds to logout.
    bool isLogoutReceipt(int receiptId) const;
};
