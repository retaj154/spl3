#include "../include/StompProtocol.h"
#include <sstream>
#include <cctype>

StompProtocol::StompProtocol()
        : subscriptionIdCounter(1), receiptIdCounter(1),
          topicToSubId(), game_reports(), receiptToPrint(),
          logoutReceiptId(-1) {}

int StompProtocol::halftimePhase(const Event &e) {
    auto &m = e.get_game_updates();
    auto it = m.find("before halftime");
    if (it == m.end()) return 2;
    string v = trim(it->second);
    for (char &c : v) c = (char)tolower(c);
    if (v == "true" || v == "1" || v == "yes") return 0;
    if (v == "false" || v == "0" || v == "no") return 1;
    return 2;
}

bool StompProtocol::eventLess(const Event &a, const Event &b) {
    int pa = halftimePhase(a);
    int pb = halftimePhase(b);

    if (pa != 2 || pb != 2) {
        if (pa == 2) pa = 1;
        if (pb == 2) pb = 1;
        if (pa != pb) return pa < pb;
    }

    if (a.get_time() != b.get_time())
        return a.get_time() < b.get_time();

    return a.get_name() < b.get_name();
}

string StompProtocol::trim(const string &s) {
    size_t b = s.find_first_not_of(" \t\r");
    if (b == string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r");
    return s.substr(b, e - b + 1);
}

bool StompProtocol::startsWith(const string &s, const string &prefix) {
    return s.rfind(prefix, 0) == 0;
}

bool StompProtocol::parseKeyValue(const string &line, string &key, string &val) {
    string t = trim(line);
    if (t.empty()) return false;
    size_t pos = t.find(':');
    if (pos == string::npos) return false;
    key = trim(t.substr(0, pos));
    val = trim(t.substr(pos + 1));
    return !key.empty();
}

string StompProtocol::processInput(const string &line, const string &username) {
    stringstream ss(line);
    string command;
    ss >> command;

    if (command == "join") {
        string game;
        ss >> game;
        if (game.empty()) return "";

        string destination = "/" + game;
        int subId = subscriptionIdCounter++;
        int receiptId = receiptIdCounter++;

        topicToSubId[destination] = subId;
        receiptToPrint[receiptId] = "Joined channel " + game;

        return "SUBSCRIBE\n"
               "destination:" + destination + "\n" +
               "id:" + to_string(subId) + "\n" +
               "receipt:" + to_string(receiptId) + "\n\n";
    }

    if (command == "exit") {
        string game;
        ss >> game;
        if (game.empty()) return "";

        string destination = "/" + game;
        auto it = topicToSubId.find(destination);
        if (it == topicToSubId.end()) return "";

        int subId = it->second;
        int receiptId = receiptIdCounter++;

        receiptToPrint[receiptId] = "Exited channel " + game;
        topicToSubId.erase(it);

        return "UNSUBSCRIBE\n"
               "id:" + to_string(subId) + "\n" +
               "receipt:" + to_string(receiptId) + "\n\n";
    }

    if (command == "add") {
        string game;
        ss >> game;
        string book;
        getline(ss, book);
        book = trim(book);
        if (game.empty() || book.empty()) return "";

        string destination = "/" + game;
        return "SEND\n"
               "destination:" + destination + "\n\n" +
               username + " wish to borrow " + book + "\n";
    }

    if (command == "logout") {
        int receiptId = receiptIdCounter++;
        logoutReceiptId = receiptId;
        receiptToPrint[receiptId] = "Logout successful";

        return "DISCONNECT\n"
               "receipt:" + to_string(receiptId) + "\n\n";
    }

    return "";
}

string StompProtocol::createReportFrame(const Event &event, const string &username, const string &fileNameHeader) {
    string destination = "/" + event.get_team_a_name() + "_" + event.get_team_b_name();

    string frame = "SEND\n";
    frame += "destination:" + destination + "\n";

    if (!fileNameHeader.empty()) {
        frame += "file:" + fileNameHeader + "\n";
    }

    frame += "\n";

    frame += "user : " + username + "\n";
    frame += "team a : " + event.get_team_a_name() + "\n";
    frame += "team b : " + event.get_team_b_name() + "\n";
    frame += "event name : " + event.get_name() + "\n";
    frame += "time : " + to_string(event.get_time()) + "\n";

    frame += "general game updates :\n";
    for (const auto &kv : event.get_game_updates()) {
        frame += kv.first + " : " + kv.second + "\n";
    }

    frame += "team a updates :\n";
    for (const auto &kv : event.get_team_a_updates()) {
        frame += kv.first + " : " + kv.second + "\n";
    }

    frame += "team b updates :\n";
    for (const auto &kv : event.get_team_b_updates()) {
        frame += kv.first + " : " + kv.second + "\n";
    }

    frame += "description :\n";
    frame += event.get_discription() + "\n";

    return frame;
}

void StompProtocol::processMessage(const string &frame) {
    string gameName;
    {
        istringstream iss(frame);
        string line;
        while (getline(iss, line)) {
            line = trim(line);
            if (line.empty()) break;
            if (startsWith(line, "destination:")) {
                string dest = trim(line.substr(string("destination:").size()));
                if (!dest.empty() && dest[0] == '/') dest = dest.substr(1);
                gameName = dest;
            }
        }
    }
    if (gameName.empty()) return;

    size_t sep = frame.find("\n\n");
    if (sep == string::npos) return;
    string body = frame.substr(sep + 2);

    istringstream bodyStream(body);
    string line;

    string user;
    string teamA;
    string teamB;
    string eventName;
    int time = 0;

    map<string, string> gameUpdates;
    map<string, string> teamAUpdates;
    map<string, string> teamBUpdates;
    string description;

    enum Section { NONE, GEN, A, B, DESC } section = NONE;

    while (getline(bodyStream, line)) {
        string t = trim(line);
        if (t.empty()) continue;

        if (t.rfind("general game updates", 0) == 0) {
            section = GEN;
            continue;
        }
        if (t.rfind("team a updates", 0) == 0) {
            section = A;
            continue;
        }
        if (t.rfind("team b updates", 0) == 0) {
            section = B;
            continue;
        }
        if (t.rfind("description", 0) == 0) {
            section = DESC;
            continue;
        }

        string key, val;
        if (section == DESC) {
            if (!description.empty()) description += "\n";
            description += line;
            continue;
        }

        if (!parseKeyValue(line, key, val)) continue;

        if (key == "user") user = val;
        else if (key == "team a") teamA = val;
        else if (key == "team b") teamB = val;
        else if (key == "event name") eventName = val;
        else if (key == "time") {
            try { time = stoi(val); } catch (...) { time = 0; }
        } else {
            if (section == GEN) gameUpdates[key] = val;
            else if (section == A) teamAUpdates[key] = val;
            else if (section == B) teamBUpdates[key] = val;
        }
    }

    if (user.empty() || teamA.empty() || teamB.empty() || eventName.empty()) return;

    Event ev(teamA, teamB, eventName, time, gameUpdates, teamAUpdates, teamBUpdates, description);
    game_reports[gameName][user].push_back(ev);

    auto &vec = game_reports[gameName][user];
    sort(vec.begin(), vec.end(), eventLess);
}

void StompProtocol::saveSentEvent(const Event &event, const string &username) {
    string gameName = event.get_team_a_name() + "_" + event.get_team_b_name();
    game_reports[gameName][username].push_back(event);
    auto &vec = game_reports[gameName][username];
    sort(vec.begin(), vec.end(), eventLess);
}

void StompProtocol::saveSummary(const string &gameName, const string &user, const string &filePath) {
    auto gameIt = game_reports.find(gameName);
    if (gameIt == game_reports.end()) return;

    auto userIt = gameIt->second.find(user);
    if (userIt == gameIt->second.end()) return;

    const vector<Event> &events = userIt->second;
    if (events.empty()) return;

    map<string, string> generalStats;
    map<string, string> aStats;
    map<string, string> bStats;

    for (const Event &e : events) {
        for (const auto &kv : e.get_game_updates()) generalStats[kv.first] = kv.second;
        for (const auto &kv : e.get_team_a_updates()) aStats[kv.first] = kv.second;
        for (const auto &kv : e.get_team_b_updates()) bStats[kv.first] = kv.second;
    }

    string teamA = events[0].get_team_a_name();
    string teamB = events[0].get_team_b_name();

    ofstream out(filePath);
    out << teamA << " vs " << teamB << "\n";
    out << "Game stats :\n";
    out << "General stats :\n";
    for (const auto &kv : generalStats) out << kv.first << ": " << kv.second << "\n";
    out << teamA << " stats :\n";
    for (const auto &kv : aStats) out << kv.first << ": " << kv.second << "\n";
    out << teamB << " stats :\n";
    for (const auto &kv : bStats) out << kv.first << ": " << kv.second << "\n";

    out << "Game event reports :\n";
    for (const Event &e : events) {
        out << e.get_time() << " - " << e.get_name() << ":\n";
        out << e.get_discription() << "\n";
    }
    out.close();
}

string StompProtocol::onReceipt(int receiptId) {
    auto it = receiptToPrint.find(receiptId);
    if (it == receiptToPrint.end()) return "";
    string msg = it->second;
    receiptToPrint.erase(it);
    return msg;
}

bool StompProtocol::isLogoutReceipt(int receiptId) const {
    return receiptId == logoutReceiptId;
}
