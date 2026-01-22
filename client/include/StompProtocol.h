#pragma once

#include "../include/ConnectionHandler.h"
#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <fstream>
#include "event.h"

using namespace std;

class StompProtocol {
private:
    int subscriptionIdCounter;
    int receiptIdCounter;

    map<string, int> topicToSubId;

    map<string, map<string, vector<Event>>> game_reports;

    map<int, string> receiptToPrint;

    int logoutReceiptId;

    static string trim(const string &s);
    static bool startsWith(const string &s, const string &prefix);
    static bool parseKeyValue(const string &line, string &key, string &val);

    
    static int halftimePhase(const Event &e);
    static bool eventLess(const Event &a, const Event &b);

public:
    StompProtocol();

    string processInput(const string &line, const string &username);

    void processMessage(const string &frame);

    string createReportFrame(const Event &event, const string &username, const string &fileNameHeader);

    void saveSummary(const string &gameName, const string &user, const string &filePath);

    void saveSentEvent(const Event &event, const string &username);

    string onReceipt(int receiptId);

    bool isLogoutReceipt(int receiptId) const;
};
