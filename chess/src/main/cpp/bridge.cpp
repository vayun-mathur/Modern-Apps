#include <cstring>
#include <iostream>
#include <jni.h>
#include <sstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "stockfish/bitboard.h"
#include "stockfish/misc.h"
#include "stockfish/position.h"
#include "stockfish/tune.h"
#include "stockfish/types.h"
#include "stockfish/uci.h"

int input_pipe[2];
int output_pipe[2];

FILE *stockfish_out = nullptr;
FILE *stockfish_in = nullptr;

void stockfish_main() {
  using namespace Stockfish;

  const int argc = 1;
  const char *argv[] = {"stockfish"};

  std::cout << engine_info() << std::endl;

  Bitboards::init();
  Position::init();

  UCIEngine uci(argc, const_cast<char **>(argv));
  Tune::init(uci.engine_options());

  uci.loop();
}

extern "C" JNIEXPORT void JNICALL Java_com_vayunmathur_games_chess_StockfishEngine_startEngine(JNIEnv *,
                                                                   jobject) {
  pipe(input_pipe);  // stdin
  pipe(output_pipe); // stdout

  dup2(input_pipe[0], STDIN_FILENO);   // stdin
  dup2(output_pipe[1], STDOUT_FILENO); // stdout

  // Wrap pipes in FILE* streams
  stockfish_in = fdopen(input_pipe[1], "w");   // We'll write commands to this
  stockfish_out = fdopen(output_pipe[0], "r"); // We'll read output from this

  std::thread([]() { stockfish_main(); }).detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_games_chess_StockfishEngine_sendCommand(JNIEnv *env, jobject, jstring jcmd) {
  const char *cmd = env->GetStringUTFChars(jcmd, nullptr);
  if (stockfish_in) {
    fprintf(stockfish_in, "%s\n", cmd); // Send command with newline
    fflush(stockfish_in);               // Important: flush to ensure it's sent
  }
  env->ReleaseStringUTFChars(jcmd, cmd);
}

// TODO: bad
extern "C" JNIEXPORT jstring JNICALL
Java_com_vayunmathur_games_chess_StockfishEngine_readOutput(JNIEnv *env, jobject) {
  char line[1024];
  if (stockfish_out && fgets(line, sizeof(line), stockfish_out)) {
    // Strip trailing newline if needed
    size_t len = strlen(line);
    if (len > 0 && line[len - 1] == '\n') {
      line[len - 1] = '\0';
    }
    return env->NewStringUTF(line);
  }

  // Nothing to read
  return env->NewStringUTF("");
}
