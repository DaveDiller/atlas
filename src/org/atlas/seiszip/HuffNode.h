
#ifndef SEISPEG_HUFF_NODE_H
#define SEISPEG_HUFF_NODE_H

class HuffNode {

private:

public:

  HuffNode();
  ~HuffNode();

  int info;
  HuffNode* left;
  HuffNode* right;

};

#endif  // SEISPEG_HUFF_NODE_H
