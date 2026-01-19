#pragma once

#include "../include/ConnectionHandler.h"
#include <string>
#include <vector>
#include <map>
#include "event.h"
using namespace std;

// TODO: implement the STOMP protocol
class StompProtocol
{
private:
    int subscriptionIdCounter; 
    int receiptIdCounter;      
    map<string, int> topicToSubId;
    std::map<std::string, std::map<std::string, std::vector<Event>>> game_reports;
public:
    StompProtocol();

    string processInput(string line, string username);

    bool shouldTerminate(string response);
    std::string createReportFrame(const Event& event, std::string username);
    void processMessage(std::string frame);
    void saveSummary(std::string gameName, std::string user, std::string filePath);
};