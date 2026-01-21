#include "../include/StompProtocol.h"
#include <iostream>
#include <sstream>
#include "../include/StompProtocol.h"
using namespace std;
#include <fstream>

StompProtocol::StompProtocol() : subscriptionIdCounter(0), receiptIdCounter(0), topicToSubId(), logoutReceiptId(-1) {}
 

string StompProtocol::processInput(string line, string username) {
    stringstream ss(line);
    string command;
    ss >> command; 

    if (command == "login") {
    string hostPort, user, pass;
    ss >> hostPort >> user >> pass;
    if (hostPort.empty() || user.empty() || pass.empty()) return "";

    return "CONNECT\n"
           "accept-version:1.2\n"
           "host:" + hostPort + "\n"
           "login:" + user + "\n"
           "passcode:" + pass + "\n"
           "\n";
}

    
    if (command == "join") {
        string destination;
        ss >> destination; 
        destination="/"+destination;
        if (destination.empty()) return "";
        
        int id = subscriptionIdCounter++;
        topicToSubId[destination] = id;

        return "SUBSCRIBE\n"
               "destination:" + destination + "\n"
               "id:" + to_string(id) + "\n"
               "receipt:" + to_string(receiptIdCounter++) + "\n"
               "\n";
    }
    
    if (command == "exit") {
        string destination;
        ss >> destination;
        destination="/"+destination;
        if (destination.empty()) return "";
        
        if (topicToSubId.find(destination) == topicToSubId.end()) {
            cout << "Error: You are not subscribed to " << destination << endl;
            return "";
        }
        
        int id = topicToSubId[destination];
        topicToSubId.erase(destination);
        
        return "UNSUBSCRIBE\n"
               "id:" + to_string(id) + "\n"
               "receipt:" + to_string(receiptIdCounter++) + "\n"
               "\n";
    }
    
    if (command == "add") {
    string destination;
    ss >> destination;
    if (destination.empty()) return "";

    string body;
    getline(ss, body);
    if (!body.empty() && body[0] == ' ') body.erase(0, 1);

    int receipt = receiptIdCounter++;

    return "SEND\n"
           "destination:/" + destination + "\n"
           "receipt:" + to_string(receipt) + "\n"
           "\n" +
           body + "\n";
}

    
    if (command == "logout") {
    logoutReceiptId = receiptIdCounter++;
    return "DISCONNECT\n"
           "receipt:" + to_string(logoutReceiptId) + "\n"
           "\n";
}


    return "";
}

bool StompProtocol::shouldTerminate(string response) {
    if (logoutReceiptId == -1) return false;
    return response.find("RECEIPT") != string::npos &&
           response.find("receipt-id:" + to_string(logoutReceiptId)) != string::npos;
}


std::string StompProtocol::createReportFrame(const Event& event, std::string username) {
    std::string body = "user: " + username + "\n";
    body += "team a: " + event.get_team_a_name() + "\n";
    body += "team b: " + event.get_team_b_name() + "\n";
    body += "event name: " + event.get_name() + "\n";
    body += "time: " + std::to_string(event.get_time()) + "\n";
    
    body += "general game updates:\n";
    for (auto const& update : event.get_game_updates()) {
        body += "    " + update.first + ": " + update.second + "\n";
    }
    
    body += "team a updates:\n";
    for (auto const& update : event.get_team_a_updates()) {
        body += "    " + update.first + ": " + update.second + "\n";
    }
    
    body += "team b updates:\n";
    for (auto const& update : event.get_team_b_updates()) {
        body += "    " + update.first + ": " + update.second + "\n";
    }
    
    body += "description:\n" + event.get_discription() + "\n";

    std::string destination = event.get_team_a_name() + "_" + event.get_team_b_name();
    return  "SEND\n"
       "destination:/" + destination + "\n"
       "\n" + body + "\n";
}
void StompProtocol::processMessage(string frame) {
    stringstream ss(frame);
    string line, gameName, user, description, eventName;
    map<string, string> gen_up, tA_up, tB_up;
    string tA, tB;
    int time = 0;

    while (getline(ss, line) && line != "") {
        if (line.find("destination:/") == 0) {
            gameName = line.substr(13);
        }
    }

    string section = "";
    while (getline(ss, line)) {
        if (line.find("user: ") == 0) user = line.substr(6);
        else if (line.find("team a: ") == 0) tA = line.substr(8);
        else if (line.find("team b: ") == 0) tB = line.substr(8);
        else if (line.find("event name: ") == 0) eventName = line.substr(12);
        else if (line.find("time: ") == 0) time = stoi(line.substr(6));
        else if (line.find("general game updates:") == 0) section = "gen";
        else if (line.find("team a updates:") == 0) section = "tA";
        else if (line.find("team b updates:") == 0) section = "tB";
        else if (line.find("description:") == 0) section = "desc";
        else if (line.find("    ") == 0) { // עיבוד עדכונים עם 4 רווחים
            size_t colon = line.find(":");
            string key = line.substr(4, colon - 4);
            string val = line.substr(colon + 2);
            if (section == "gen") gen_up[key] = val;
            else if (section == "tA") tA_up[key] = val;
            else if (section == "tB") tB_up[key] = val;
        }
        else if (section == "desc") description += line + "\n";
    }

    Event e(tA, tB, eventName, time, gen_up, tA_up, tB_up, description);
    game_reports[gameName][user].push_back(e);
}
void StompProtocol::saveSummary(string gameName, string user, string filePath) {
    ofstream outFile(filePath);
    if (!outFile.is_open()) return;

    auto& events = game_reports[gameName][user];
    sort(events.begin(), events.end(), [](const Event& a, const Event& b) {
        return a.get_time() < b.get_time();
    });

    string displayGame = gameName;
    size_t underscore = displayGame.find('_');
    if (underscore != string::npos) displayGame.replace(underscore, 1, " vs ");
    outFile << displayGame << "\n";

    map<string, string> final_gen, final_tA, final_tB;
    for (const auto& e : events) {
        for (auto const& up : e.get_game_updates()) final_gen[up.first] = up.second;
        for (auto const& up : e.get_team_a_updates()) final_tA[up.first] = up.second;
        for (auto const& up : e.get_team_b_updates()) final_tB[up.first] = up.second;
    }

    outFile << "Game stats:\nGeneral stats:\n";
    for (auto const& stat : final_gen) outFile << "    " << stat.first << ": " << stat.second << "\n";
    
    outFile << events[0].get_team_a_name() << " stats:\n";
    for (auto const& stat : final_tA) outFile << "    " << stat.first << ": " << stat.second << "\n";
    
    outFile << events[0].get_team_b_name() << " stats:\n";
    for (auto const& stat : final_tB) outFile << "    " << stat.first << ": " << stat.second << "\n";

    outFile << "Game event reports:\n";
    for (const auto& e : events) {
        outFile << e.get_time() << ": " << e.get_name() << "\n";
        string d = e.get_discription();
        outFile << (d.length() > 27 ? d.substr(0, 27) + "..." : d) << "\n\n";
    }
    outFile.close();


}

