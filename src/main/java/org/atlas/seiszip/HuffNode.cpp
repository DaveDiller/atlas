
/*******************************************************************************
 * For documentation of the public C++ methods in this code, see the accompanying
 * Java file with the same class name.
 ********************************************************************************/

#include "HuffNode.h"

HuffNode::HuffNode() {

  info = -1;
  left = nullptr;
  right = nullptr;
}

HuffNode::~HuffNode() {

  if (left != nullptr)
    delete left;
  if (right != nullptr)
    delete right;
}
