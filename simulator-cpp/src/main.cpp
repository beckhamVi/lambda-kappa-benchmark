// Simulador de eventos e-commerce -> Kafka
// Genera eventos con el mismo formato que el CSV de REES46 (una linea CSV por mensaje).
#include <librdkafka/rdkafkacpp.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <random>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// ---------------------------------------------------------------- configuracion
struct Config {
    std::string brokers = "localhost:9092";
    std::string topic = "events";
    std::string replayCsv;                       // si se indica, publica este CSV en vez de generar eventos
    std::string catalog = "../data/sample.csv";  // se usa para copiar productos y popularidad reales
    std::string startDate = "2020-05-01";        // fecha simulada de inicio (despues del CSV historico)
    long long total = 1000000;                   // eventos a enviar
    int rate = 5000;                             // eventos por segundo (0 = maxima velocidad)
    unsigned seed = 42;                          // semilla fija => corridas reproducibles
    double dupRate = 0.002;                      // probabilidad de evento duplicado
    double invalidRate = 0.001;                  // probabilidad de precio invalido (0 o negativo)
    double oooRate = 0.01;                       // probabilidad de evento fuera de orden
    int simStepMs = 100;                         // avance del reloj simulado por evento
    int maxSessions = 2000;                      // sesiones activas a la vez
};

static Config parseArgs(int argc, char** argv) {
    Config c;
    for (int i = 1; i + 1 < argc; i += 2) {
        std::string k = argv[i];
        std::string v = argv[i + 1];
        if (k == "--brokers") c.brokers = v;
        else if (k == "--topic") c.topic = v;
        else if (k == "--catalog") c.catalog = v;
        else if (k == "--replay-csv") c.replayCsv = v;
        else if (k == "--start-date") c.startDate = v;
        else if (k == "--total") c.total = std::stoll(v);
        else if (k == "--rate") c.rate = std::stoi(v);
        else if (k == "--seed") c.seed = static_cast<unsigned>(std::stoul(v));
        else if (k == "--dup-rate") c.dupRate = std::stod(v);
        else if (k == "--invalid-rate") c.invalidRate = std::stod(v);
        else if (k == "--ooo-rate") c.oooRate = std::stod(v);
        else if (k == "--sim-step-ms") c.simStepMs = std::stoi(v);
        else if (k == "--max-sessions") c.maxSessions = std::stoi(v);
        else {
            std::cerr << "Opcion desconocida: " << k << "\n";
            std::exit(1);
        }
    }
    return c;
}

// ---------------------------------------------------------------- fechas (sin depender de gmtime)
static int64_t daysFromCivil(int y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return static_cast<int64_t>(era) * 146097 + static_cast<int64_t>(doe) - 719468;
}

static void civilFromDays(int64_t z, int& y, unsigned& m, unsigned& d) {
    z += 719468;
    const int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    const unsigned doe = static_cast<unsigned>(z - era * 146097);
    const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    y = static_cast<int>(yoe) + static_cast<int>(era) * 400;
    const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned mp = (5 * doy + 2) / 153;
    d = doy - (153 * mp + 2) / 5 + 1;
    m = mp < 10 ? mp + 3 : mp - 9;
    y += (m <= 2);
}

static std::string formatTime(int64_t epochSec) {
    int64_t days = epochSec / 86400;
    int64_t rem = epochSec % 86400;
    if (rem < 0) { rem += 86400; days -= 1; }
    int y; unsigned m, d;
    civilFromDays(days, y, m, d);
    char buf[40];
    std::snprintf(buf, sizeof buf, "%04d-%02u-%02u %02d:%02d:%02d UTC",
                  y, m, d, static_cast<int>(rem / 3600),
                  static_cast<int>((rem % 3600) / 60), static_cast<int>(rem % 60));
    return buf;
}

static int64_t parseStartDate(const std::string& s) {
    int y = 0, m = 0, d = 0;
    if (std::sscanf(s.c_str(), "%d-%d-%d", &y, &m, &d) != 3) {
        std::cerr << "Fecha invalida (usa YYYY-MM-DD): " << s << "\n";
        std::exit(1);
    }
    return daysFromCivil(y, static_cast<unsigned>(m), static_cast<unsigned>(d)) * 86400;
}

// ---------------------------------------------------------------- catalogo de productos
struct Product {
    long long id;
    long long categoryId;
    std::string categoryCode;
    std::string brand;
    double price;
};

static std::vector<std::string> splitCsv(const std::string& line) {
    std::vector<std::string> out;
    std::string cur;
    for (char ch : line) {
        if (ch == ',') { out.push_back(cur); cur.clear(); }
        else if (ch != '\r') cur.push_back(ch);
    }
    out.push_back(cur);
    return out;
}

// Lee el CSV real: cada producto distinto se guarda una vez y su "peso" es cuantas veces aparece
// (asi la popularidad simulada se parece a la real: pocos productos concentran las vistas).
static bool loadCatalog(const std::string& path, std::vector<Product>& products,
                        std::vector<double>& weights) {
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    std::getline(in, line);  // cabecera
    std::unordered_map<long long, size_t> index;
    while (std::getline(in, line)) {
        auto f = splitCsv(line);
        if (f.size() != 9) continue;
        try {
            double price = std::stod(f[6]);
            if (price <= 0) continue;
            long long pid = std::stoll(f[2]);
            auto it = index.find(pid);
            if (it == index.end()) {
                index[pid] = products.size();
                products.push_back({pid, std::stoll(f[3]), f[4], f[5], price});
                weights.push_back(1.0);
            } else {
                weights[it->second] += 1.0;
            }
        } catch (...) {
            continue;
        }
    }
    return !products.empty();
}

static void syntheticCatalog(std::mt19937_64& rng, std::vector<Product>& products,
                             std::vector<double>& weights) {
    const char* cats[] = {"electronics.audio.headphone", "apparel.shoes", "appliances.kitchen.washer",
                          "computers.notebook", "construction.tools.light", "sport.bicycle"};
    const char* brands[] = {"samsung", "apple", "xiaomi", "lg", "sony", ""};
    std::uniform_real_distribution<double> price(5.0, 1500.0);
    for (int i = 0; i < 2000; ++i) {
        products.push_back({1000000 + i, 2053013550000000000LL + (i % 6), cats[i % 6],
                            brands[i % 6], std::round(price(rng) * 100) / 100});
        weights.push_back(1.0 / (1 + i % 200));
    }
}

// ---------------------------------------------------------------- generador de eventos
struct Session {
    long long userId;
    std::string id;
    std::vector<size_t> viewed;
    std::vector<size_t> cart;
};

struct Generated {
    std::string line;
    std::string key;
};

class EventGenerator {
public:
    EventGenerator(const Config& cfg, std::vector<Product> products, std::vector<double> weights)
        : cfg_(cfg),
          rng_(cfg.seed),
          products_(std::move(products)),
          popularity_(weights.begin(), weights.end()),
          startSec_(parseStartDate(cfg.startDate)) {
        sessions_.reserve(static_cast<size_t>(cfg.maxSessions));
    }

    Generated next() {
        simMs_ += cfg_.simStepMs;

        if (!last_.line.empty() && uni_(rng_) < cfg_.dupRate) {
            return last_;  // duplicado exacto
        }

        size_t si;
        if (sessions_.size() < static_cast<size_t>(cfg_.maxSessions) || uni_(rng_) < 0.05) {
            if (sessions_.size() < static_cast<size_t>(cfg_.maxSessions)) {
                sessions_.push_back(newSession());
                si = sessions_.size() - 1;
            } else {
                si = pick(sessions_.size());
                sessions_[si] = newSession();
            }
        } else {
            si = pick(sessions_.size());
        }
        Session& s = sessions_[si];

        const char* type = "view";
        size_t pi = 0;
        double u = uni_(rng_);
        if (!s.cart.empty() && u < 0.06) {
            type = "purchase";
            pi = s.cart.back();
            s.cart.pop_back();
        } else if (!s.cart.empty() && u < 0.07) {
            type = "remove_from_cart";
            pi = s.cart.back();
            s.cart.pop_back();
        } else if (!s.viewed.empty() && u < 0.11) {
            type = "cart";
            pi = s.viewed[pick(s.viewed.size())];
            s.cart.push_back(pi);
        } else {
            pi = popularity_(rng_);
            s.viewed.push_back(pi);
        }

        const Product& p = products_[pi];
        double price = p.price;
        if (uni_(rng_) < cfg_.invalidRate) {
            price = (uni_(rng_) < 0.5) ? 0.0 : -p.price;  // dato basura a proposito
        }

        int64_t t = startSec_ + simMs_ / 1000;
        if (uni_(rng_) < cfg_.oooRate) {
            t -= 1 + static_cast<int64_t>(pick(300));  // llega hasta 5 min tarde
        }

        char priceBuf[32];
        std::snprintf(priceBuf, sizeof priceBuf, "%.2f", price);

        Generated g;
        g.line.reserve(160);
        g.line.append(formatTime(t)).append(",").append(type).append(",")
            .append(std::to_string(p.id)).append(",").append(std::to_string(p.categoryId)).append(",")
            .append(p.categoryCode).append(",").append(p.brand).append(",")
            .append(priceBuf).append(",").append(std::to_string(s.userId)).append(",").append(s.id);
        g.key = std::to_string(s.userId);

        if (uni_(rng_) < 0.04) {  // la sesion termina
            sessions_[si] = newSession();
        }
        last_ = g;
        return g;
    }

private:
    size_t pick(size_t n) { return static_cast<size_t>(rng_() % n); }

    Session newSession() {
        Session s;
        s.userId = 500000000LL + static_cast<long long>(rng_() % 300000);
        s.id = uuid();
        return s;
    }

    std::string uuid() {
        static const char* hex = "0123456789abcdef";
        std::string out;
        const int groups[] = {8, 4, 4, 4, 12};
        for (int g = 0; g < 5; ++g) {
            if (g) out.push_back('-');
            for (int i = 0; i < groups[g]; ++i) out.push_back(hex[rng_() % 16]);
        }
        return out;
    }

    Config cfg_;
    std::mt19937_64 rng_;
    std::uniform_real_distribution<double> uni_{0.0, 1.0};
    std::vector<Product> products_;
    std::discrete_distribution<size_t> popularity_;
    std::vector<Session> sessions_;
    int64_t startSec_;
    int64_t simMs_ = 0;
    Generated last_;
};

// ---------------------------------------------------------------- Kafka
class DeliveryCb : public RdKafka::DeliveryReportCb {
public:
    std::atomic<long long> ok{0};
    std::atomic<long long> failed{0};
    void dr_cb(RdKafka::Message& m) override {
        if (m.err()) failed++; else ok++;
    }
};

int main(int argc, char** argv) {
    Config cfg = parseArgs(argc, argv);

    std::vector<Product> products;
    std::vector<double> weights;
    std::mt19937_64 tmp(cfg.seed);
    if (loadCatalog(cfg.catalog, products, weights)) {
        std::cout << "Catalogo cargado: " << products.size() << " productos desde " << cfg.catalog << "\n";
    } else {
        std::cout << "No se pudo leer " << cfg.catalog << ", uso catalogo sintetico\n";
        syntheticCatalog(tmp, products, weights);
    }
    EventGenerator gen(cfg, std::move(products), std::move(weights));

    std::string err;
    std::unique_ptr<RdKafka::Conf> conf(RdKafka::Conf::create(RdKafka::Conf::CONF_GLOBAL));
    auto set = [&](const char* k, const std::string& v) {
        if (conf->set(k, v, err) != RdKafka::Conf::CONF_OK) {
            std::cerr << "Config " << k << ": " << err << "\n";
            std::exit(1);
        }
    };
    set("bootstrap.servers", cfg.brokers);
    set("linger.ms", "5");
    set("batch.num.messages", "10000");
    set("queue.buffering.max.messages", "1000000");
    set("compression.type", "lz4");

    DeliveryCb dr;
    if (conf->set("dr_cb", static_cast<RdKafka::DeliveryReportCb*>(&dr), err) != RdKafka::Conf::CONF_OK) {
        std::cerr << err << "\n";
        return 1;
    }
    std::unique_ptr<RdKafka::Producer> producer(RdKafka::Producer::create(conf.get(), err));
    if (!producer) {
        std::cerr << "No se pudo crear el productor: " << err << "\n";
        return 1;
    }

    std::ifstream replay;
    if (!cfg.replayCsv.empty()) {
        replay.open(cfg.replayCsv);
        if (!replay) { std::cerr << "No se pudo abrir " << cfg.replayCsv << "\n"; return 1; }
        std::string header;
        std::getline(replay, header);
        cfg.total = std::numeric_limits<long long>::max();  // termina al acabar el archivo
    }

    std::cout << "Enviando " << cfg.total << " eventos a " << cfg.brokers << " topic=" << cfg.topic
              << " rate=" << (cfg.rate > 0 ? std::to_string(cfg.rate) + "/s" : "maximo")
              << " seed=" << cfg.seed << "\n";

    using clock = std::chrono::steady_clock;
    const auto t0 = clock::now();
    auto next = t0;
    auto lastReport = t0;
    long long lastSent = 0;
    long long sent = 0;
    const auto tick = std::chrono::milliseconds(10);
    const long long perTick = cfg.rate > 0 ? std::max(1, cfg.rate / 100) : 5000;

    while (sent < cfg.total) {
        for (long long i = 0; i < perTick && sent < cfg.total; ++i) {
            Generated e;
            if (replay.is_open()) {
                if (!std::getline(replay, e.line)) { cfg.total = sent; break; }
                if (!e.line.empty() && e.line.back() == '\r') e.line.pop_back();
                auto f = splitCsv(e.line);
                e.key = f.size() == 9 ? f[7] : "";
            } else {
                e = gen.next();
            }
            for (;;) {
                RdKafka::ErrorCode rc = producer->produce(
                    cfg.topic, RdKafka::Topic::PARTITION_UA, RdKafka::Producer::RK_MSG_COPY,
                    const_cast<char*>(e.line.data()), e.line.size(),
                    e.key.data(), e.key.size(), 0, nullptr);
                if (rc == RdKafka::ERR_NO_ERROR) break;
                if (rc == RdKafka::ERR__QUEUE_FULL) { producer->poll(100); continue; }
                std::cerr << "Error al enviar: " << RdKafka::err2str(rc) << "\n";
                dr.failed++;
                break;
            }
            ++sent;
        }
        producer->poll(0);

        if (cfg.rate > 0) {
            next += tick;
            std::this_thread::sleep_until(next);
        }
        auto now = clock::now();
        if (now - lastReport >= std::chrono::seconds(1)) {
            double secs = std::chrono::duration<double>(now - lastReport).count();
            std::cout << "enviados=" << sent << "  tasa=" << static_cast<long long>((sent - lastSent) / secs)
                      << "/s  confirmados=" << dr.ok.load() << "  fallidos=" << dr.failed.load() << "\n";
            lastReport = now;
            lastSent = sent;
        }
    }

    producer->flush(30000);
    double total = std::chrono::duration<double>(clock::now() - t0).count();
    std::cout << "\n===== RESULTADO SIMULADOR =====\n"
              << "Eventos enviados : " << sent << "\n"
              << "Confirmados      : " << dr.ok.load() << "\n"
              << "Fallidos         : " << dr.failed.load() << "\n"
              << "Tiempo           : " << total << " s\n"
              << "Tasa real        : " << static_cast<long long>(sent / total) << " eventos/s\n"
              << "===============================\n";
    return dr.failed.load() == 0 ? 0 : 2;
}