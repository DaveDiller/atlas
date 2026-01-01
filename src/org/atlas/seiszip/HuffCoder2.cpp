
/*******************************************************************************
 * For documentation of the public C++ methods in this code, see the accompanying
 * Java file with the same class name.
 ********************************************************************************/

#include "HuffCoder2.h"
#include "CompressionUtil2.h"

#include <stdint.h>
#include <assert.h>
#include <cstdio>

#define MAXVALUE 256
#define MAXVALUEP1 257

#define c_expectedHuffTreeChecksum 32640L

#define HUFF_NODE_COOKIE 198765403
#define HUFF_CODER_COOKIE 187065432


const int HuffCoder2::c_huffTable[] = {
    3,     47538, 16548, 9566,  6868,  5231,  4206,  2748,
    1106,  442,   192,   104,   85,    118,   157,   160,
    56,    38,    39,    45,    72,    64,    95,    55,
    50,    81,    113,   161,   268,   238,   182,   104,
    430,   883,   1173,  1242,  1201,  870,   574,   281,
    452,   927,   1213,  1300,  1194,  931,   791,   565,
    330,   307,   285,   306,   358,   399,   412,   366,
    138,   62,    39,    53,    67,    76,    137,   127,
    49,    11,    9,     2,     1,     1,     3,     9,
    6,     2,     1,     1,     2,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     2,     3,     4,     1,     3,
    2,     739,   8,     3,     8,     7,     8,     4,
    4,     6,     2,     6,     7,     12,    17,    9,
    2,     1,     1,     3,     5,     9,     14,    36,
    1,     2,     1,     1,     1,     1,     1,     2,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     2,     1,     3,     3,     17,    37,
    95,    162,   271,   385,   484,   605,   771,   964,
    1190,  1329,  1584,  1924,  2511,  3212,  4340,  5972,
    8918,  14712, 28038, 88353, 8,     88613, 28514, 14711,
    8918,  6080,  4316,  3251,  2554,  2053,  1681,  1450,
    1166,  933,   757,   695,   486,   386,   313,   162,
    102,   53,    21,    5,     3,     3,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     1,
    1,     1,     1,     1,     1,     1,     1,     2,
    1,     3,     9,     11,    9,     9,     5,     3,
    1,     7,     7,     22,    41,    30,    17,    4,
    2,     4,     6,     24,    23,    23,    13,    9,
    6,     2,     3,     8,     10,    17,    25,    51,
    1
};


const int HuffCoder2::c_hdrHuffCount_NOT_USED[] = {
    436720,    27642,    17681,    28236,    27379,    12856,    26168,    13712,
    28964,     14877,    18072,    14102,    36067,    14056,    19176,    29641,
    29244,     14726,    19781,    22378,    29263,    15115,    20058,    15036,
    30312,     23339,    19300,    15741,    44398,    15860,    43389,    23187,
    183578,    16155,    21334,    15789,    30555,    23564,    20481,    16643,
    46428,     17680,    21201,    16721,    39066,    17309,    22365,    32449,
    48273,     24969,    29628,    24788,    31668,    32854,    22260,    17814,
    39557,     17905,    21085,    16627,    30126,    15565,    28063,    15001,
    340358,    29861,    18228,    13661,    27705,    20156,    77576,    11670,
    36464,     33816,    15619,    25271,    23219,    12331,    40695,    44990,
    84794,     85983,    82110,    79328,    111409,   172587,   207508,   197163,
    177122,    115141,   55359,    9373,     22740,    9867,     629214,   9777,
    177395,    25231,    13168,    9721,     30713,    9406,     16108,    29536,
    26692,     11043,    23184,    10564,    31734,    10469,    15182,    10507,
    34130,     11419,    13947,    25808,    24690,    8975,     13829,    15646,
    24159,     21563,    28785,    9059,     22646,    16705,    13569,    36416,
    6880593,   123074,   26121,    15104,    43299,    37769,    35561,    12805,
    35661,     42232,    18766,    35983,    42555,    20526,    16417,    12812,
    34923,     12050,    38108,    18658,    25616,    10814,    15837,    10478,
    25188,     26511,    375358,   257449,   86563,    15732,    29035,    24115,
    198887,    13033,    37457,    10609,    23193,    24103,    14498,    16557,
    234858,    16458,    13796,    24425,    23585,    8755,     13426,    8368,
    39676,     8638,     28089,    8232,     29729,    8628,     12516,    8059,
    36729,     8050,     12246,    22557,    83054,    7156,     26319,    37030,
    359111,    1589176,  58682,    71921,    513192,   372901,   26121,    7081,
    630200,    34551,    633879,   21191,    28656,    5889,     10287,    13703,
    60962,     21462,    10403,    5718,     36872,    10542,    10129,    20171,
    19474,     5619,     9525,     619334,   20447,    20421,    9936,     10972,
    183338,    5813,     10111,    5381,     33647,    5313,     10097,    4956,
    19944,     5370,     31939,    5641,     19688,    20702,    9659,     5611,
    35809,     6403,     11057,    6714,     28402,    14568,    26962,    8078,
    32592,     35051,    28500,    9299,     24242,    25033,    14753,    55899,
    1
};


const int HuffCoder2::c_hdrHuffCountImproved_NOT_USED[] = {
    11282418,    429207,    83797,    47928,    101174,    83357,    84384,    25643,
    128345,    83796,    62337,    139406,    125425,    52918,    52668,    35715,
    132953,    29765,    98509,    67345,    64043,    22765,    56847,    30684,
    101765,    125765,    1113356,    779415,    329811,    52338,    139729,    69282,
    1038189,    26320,    82871,    27887,    52581,    67616,    47183,    71938,
    1076306,    66661,    33723,    38843,    69312,    24360,    33980,    21235,
    81502,    10888,    89733,    32096,    77695,    32433,    26417,    33787,
    123185,    13166,    30938,    57323,    212240,    28563,    82492,    126379,
    1051908,    4487493,    205982,    261637,    1437056,    1188072,    56927,    12506,
    2291115,    131682,    2362043,    60963,    88901,    13106,    20999,    71791,
    213536,    72083,    33400,    15060,    95256,    30216,    28112,    65134,
    45594,    147,    23169,    1710734,    66933,    52609,    34100,    23229,
    431197,    11674,    20251,    12129,    134773,    17,    36141,    1,
    46880,    8248,    138693,    11217,    69866,    45828,    28084,    14425,
    128053,    14351,    27389,    13020,    144301,    57677,    42758,    26443,
    98624,    139273,    123299,    34232,    58176,    67448,    64242,    172200,
    2326125,    131089,    38936,    53768,    68579,    20355,    60089,    24065,
    137368,    56258,    58743,    36636,    137793,    36888,    43563,    105896,
    140074,    43942,    67270,    38516,    124853,    62262,    66269,    59370,
    139081,    69265,    59034,    65157,    108454,    50912,    138450,    52720,
    905799,    46596,    67882,    48406,    113455,    58166,    53004,    53597,
    79502,    63677,    54887,    36793,    94872,    70752,    43875,    115025,
    76869,    70339,    144749,    49382,    145247,    117705,    38680,    40433,
    95812,    60977,    126846,    55407,    114039,    60976,    138401,    40626,
    768941,    125346,    42565,    26465,    131534,    44097,    287231,    27318,
    92771,    88187,    64089,    70310,    44999,    35871,    124682,    137809,
    231580,    190241,    179540,    204832,    450892,    411601,    792139,    1113272,
    557099,    450336,    105224,    26001,    69814,    19425,    2517237,    28402,
    579119,    57067,    25691,    32616,    107753,    32674,    43471,    139286,
    132666,    32605,    63140,    34332,    72889,    27763,    47048,    27150,
    140897,    18185,    28786,    62985,    71659,    33026,    53637,    36236,
    58152,    57819,    116998,    34030,    51186,    49182,    33992,    103664,
    1
};


/**
 * This method is only called during the constructor.
 *
 * Arrgh - the arguments have not been documented historically.
 */
static void downHeap(int* count, int* heap, int N, int k) {

  int j, v, halfN;
		
  halfN = N >> 1;  // halfN = N/2.
  v = heap[k];
  while (k <= halfN) {
    j = k + k;
    if (j < N  &&  (count[heap[j]] > count[heap[j+1]])) j++;
    if (count[v] < count[heap[j]]) break;
    heap[k] = heap[j];
    k = j;
  }
  heap[k] = v;
}


/**
 * Constructs a Huffman table.  This method is only called during the constructor.
 *
 * @param  count  the Huffman value count.
 * @param  heap  the heap (?).
 * @param  parent  the parent array (?).
 * @param  huffCode  the Huffman codes (?).
 * @param  huffLen  the Huffman lengths (?).
 */
static void huffTableMake(int* count, int* heap, int* parent, int* huffCode, int* huffLen) {

  int i, j, k, N, t, x;
		
  // Initialize the heap.
  N = 0;
  for (i=0; i<=MAXVALUE; i++) {
    if (count[i] != 0) {  // count[i] will never be 0 with the precomputed table.
      heap[++N] = i;
    }
  }

  for (k=N; k>0; k--)
    downHeap(count, heap, N, k);
	    
  // Construct the heap.
  for (;;) {
    t = heap[1];
    heap[1] = heap[N--];
    downHeap(count, heap, N, 1);
    count[MAXVALUE+N] = count[heap[1]] + count[t];
    parent[t] = MAXVALUE + N;
    parent[heap[1]] = -MAXVALUE - N;
    heap[1] = MAXVALUE + N;
    downHeap(count, heap, N, 1);
    if (N == 1) break;
  }
  parent[MAXVALUE+N] = 0;

  // Build the huffCode and huffLen arrays.
  for (k=0; k<=MAXVALUE; k++) {
    if (count[k] == 0) {
      huffCode[k] = 0;
      huffLen[k] = 0;
    } else {
      i = 0;
      j = 1;
      t = parent[k];
      x = 0;
      for (;;) {
	if ( t < 0 ) {
	  x += j;
	  t = -t;
	}
	t = parent[t];
	j = j + j;
	i++;
	if (t == 0) break;
      }
      huffCode[k] = x;
      huffLen[k] = i;
    }
  }

  return;  
}


/**
 * Adds a node to the Huffman tree.  This method is only called during the constructor.
 *
 * @param  x  input node.
 * @param  v  the value to add (?).
 * @param  l  the length to add (?).
 * @param  info  the info value (?).
 * @return  a Huffman node.
 */
static HuffNode* addTree(HuffNode* x, int v, int l, int info) {

  int i;

  for (;;) {

    if (x == nullptr)
      x = new HuffNode();

    // Get the bits.
    // i = (v >> (l-1)) & ~(~0 << 1);  // The compiler says "warning: left shift of negative value [-Wshift-negative-value]".
    i = (v >> (l - 1)) & 1;  // ChatGPT says this avoids the undefine behavior, is faster, and expresses the same logic clearly.
    
    if (i == 0) {  // Found a potential left branch.
      if (x->left == nullptr)  // We need to create it.
	x->left = new HuffNode();
      x = x->left;  // Move into the left branch.
    } else {
      // Found a potential right branch.
      if (x->right == nullptr)  // We need to create it.
	x->right = new HuffNode();
      x = x->right;  // Move into the right branch.
    }
    l--;
    if (l == 0) break;
  }

  // Terminating node, so set its info value (to something other than -1).
  x->info = info;
  return x;
}


static int64_t getHuffTreeCheckSum(HuffNode* huffNode, int64_t currentSum, const char* message) {

  if (huffNode == nullptr)
    return currentSum;

  currentSum += (int64_t)huffNode->info;

  if (huffNode->left != nullptr)
    currentSum = getHuffTreeCheckSum(huffNode->left, currentSum, message);

  if (huffNode->right != nullptr)
    currentSum = getHuffTreeCheckSum(huffNode->right, currentSum, message);

  return currentSum;
}


/**
 * Builds the Huffman tree.  This method is only called during the constructor.
 *
 * @return  the root Huffman node.
 */
static HuffNode* buildHuffmanTree(int* huffCode, int* huffLen) {

  HuffNode* root = new HuffNode();

  for (int i=0; i<=MAXVALUE; i++) {
    if (huffLen[i] != 0) {
      addTree(root, huffCode[i], huffLen[i], i);
    }
  }

  assert(getHuffTreeCheckSum(root, 0L, "Sanity check 1") == c_expectedHuffTreeChecksum);

  return root;
}


static void freeHuffmanTree(HuffNode* huffNode) {

  if (huffNode == nullptr)
    return;

  if (huffNode->left != nullptr) {
    freeHuffmanTree(huffNode->left);
    huffNode->left = nullptr;  // It was already deleted in the recursive call to freeHuffmanTree().
  }
  if (huffNode->right != nullptr) {
    freeHuffmanTree(huffNode->right);
    huffNode->right = nullptr;  // It was already deleted in the recursive call to freeHuffmanTree().
  }

  delete huffNode;
}


/**
 * Constructor with default Huffman table.
 */
HuffCoder2::HuffCoder2() : HuffCoder2(c_huffTable) {

  if (VERBOSE_OBJECT_LIFETIME)
    printf("this = %p was created ...\n", (void*)this);
}


/**
 * Constructor.
 *
 * @param  huffTable  the Huffman table.
 */
HuffCoder2::HuffCoder2(const int* huffTable) {

  if (VERBOSE_OBJECT_LIFETIME)
    printf("this = %p was created ...\n", (void*)this);

  // const int* huffTable = c_huffTable;

  _cookie = HUFF_CODER_COOKIE;
  _globalRoot = nullptr;
  _huffCode = new int[MAXVALUEP1]{0};
  _huffLen = new int[MAXVALUEP1]{0};
  _bVals = new char[4]{(char)0};

  // Create the Huffman table.
  int* count = new int[2*MAXVALUEP1]{0};
  for (int i=0; i<MAXVALUE+1; i++)
    count[i] = huffTable[i];
  int* heap = new int[MAXVALUEP1+1]{0};
  int* parent = new int[2*MAXVALUEP1]{0};

  huffTableMake(count, heap, parent, _huffCode, _huffLen);

  _globalRoot = buildHuffmanTree(_huffCode, _huffLen);

  assert(getHuffTreeCheckSum(_globalRoot, 0L, "Sanity check 2") == c_expectedHuffTreeChecksum);

  delete[] parent;
  delete[] heap;
  delete[] count;
}


/**
 * Destructor.
 */
HuffCoder2::~HuffCoder2() {

  if (VERBOSE_OBJECT_LIFETIME)
    printf("this = %p was destroyed ...\n", (void*)this);

  assert(_cookie == HUFF_CODER_COOKIE);

  delete[] _bVals;
  delete[] _huffLen;
  delete[] _huffCode;
  freeHuffmanTree(_globalRoot);
}


/**
 * Applies Huffman coding on a byte array, using pre-computed table.
 *
 * @param  runLengthEncodedData  run-length encoded data.
 * @param  ninputBytes  number of input bytes.
 * @param  huffEncodedData  Huffman encoded data.
 * @param  index  starting index in the Huffman encoded data.
 * @param  outputBufferSize  the size of the output buffer.
 * @return  the number of output bytes, or 0 if the output buffer is too small.
 */
int HuffCoder2::huffEncode(char* runLengthEncodedData, int ninputBytes, char* huffEncodedData, int index, int outputBufferSize) {

  assert(_cookie == HUFF_CODER_COOKIE);

  short curBit = 7;
  int dvalue;
  int curByte = 0;
  int theByte = 0;

  for (int k=0; k<=ninputBytes; k++) {

    if (k == ninputBytes) {
      // Beyond the last input.
      dvalue = MAXVALUE;
    } else {
      // Faster to avoid function call.
      // dvalue = unsignedByte(runLengthEncodedData[k]);
      dvalue = runLengthEncodedData[k];
      if (dvalue < 0) dvalue += 256;
    }

    int currentCode = _huffCode[dvalue];
    int currentLength = _huffLen[dvalue];
	    
    for (int j=currentLength-1; j>=0; --j) {
      if (((currentCode >> j) & 1) != 0)
	theByte |= (char)(128 >> curBit);
      if (--curBit < 0) {
	if (curByte >= outputBufferSize) return 0;  // Overflow!
	huffEncodedData[index+curByte] = (char)theByte;
	curByte++;
	theByte = 0;
	curBit = 7;
      }
    }

  }  // End of loop over all bytes in the input array.

  // Flush the last bits unless we just wrote out last byte exactly.
  if (curBit != 7) {
    if (curByte >= outputBufferSize) return 0;  // Overflow!
    huffEncodedData[index+curByte] = (char)theByte;
    curByte++;
  }

  return curByte;
}


/**
 * Applies Huffman coding on an int array, using pre-computed table.
 *
 * @param  runLengthEncodedData  run-length encoded data.
 * @param  ninputInts  number of input ints.
 * @param  huffEncodedData  Huffman encoded data.
 * @param  index  starting index in the Huffman encoded data.
 * @param  outputBufferSize  the size of the output buffer.
 * @return  the number of output bytes, or 0 if the output buffer is too small.
 */
int HuffCoder2::huffEncode(int* runLengthEncodedData, int ninputInts, char* huffEncodedData, int index, int outputBufferSize) {

  assert(_cookie == HUFF_CODER_COOKIE);

  short curBit = 7;
  int dvalue;
  int curByte = 0;
  int theByte = 0;

  for (int k=0; k<=ninputInts; k++) {

    int n;
    if (k < ninputInts) {
      stuffIntInBytes(runLengthEncodedData[k], _bVals, 0);
      n = 4;
    } else {
      n = 1;
    }

    for (int byteInWord=0; byteInWord<n; byteInWord++) {

      if (k == ninputInts) {
	// Beyond the last input.
	dvalue = MAXVALUE;
      } else {
	// Faster to avoid function call.
	// dvalue = unsignedByte(_bVals[byteInWord]);
	dvalue = _bVals[byteInWord];
	if (dvalue < 0) dvalue += 256;
      }

      int currentCode = _huffCode[dvalue];
      int currentLength = _huffLen[dvalue];
	    
      for (int j=currentLength-1; j>=0; --j) {
	if (((currentCode >> j) & 1) != 0)
	  theByte |= (char)(128 >> curBit);
	if (--curBit < 0) {
	  if (curByte >= outputBufferSize) return 0;  // Overflow!
	  huffEncodedData[index+curByte] = (char)theByte;
	  curByte++;
	  theByte = 0;
	  curBit = 7;
	}
      }

    }  // End of loop over 4 bytes in each integer.

  }  // End of loop over all integers in the input array.

  // Flush the last bits unless we just wrote out last byte exactly.
  if (curBit != 7) {
    if (curByte >= outputBufferSize) return 0;  // Overflow!
    huffEncodedData[index+curByte] = (char)theByte;
    curByte++;
  }

  return curByte;
}


/**
 * Removes Huffman coding, using pre-computed tree.
 *
 * @param  huffEncodedData  input Huffman encoded byte data.
 * @param  index  starting index in the Huffman encoded data.
 * @param  cHout  output byte data (in need of run-length decoding).
 * @param  outputBufferSize  the size of the output buffer.
 * @return  the number of output bytes, or -1 if the output buffer is too small.
 */
int HuffCoder2::huffDecode(const char* huffEncodedData, int index, char* cHout, int outputBufferSize) {

  // assert(getHuffTreeCheckSum(_globalRoot, 0L, "huffDecode 1") == c_expectedHuffTreeChecksum);

  // Decode the message.
  int nbytesRead = 0;
  int k = 0;
  HuffNode* z = _globalRoot;
  for (;;) {
	    
    // curChar = unsignedByte(huffEncodedData[index+nbytesRead]);
    int curChar = (int)huffEncodedData[index+nbytesRead];
    if (curChar < 0) curChar += 256;
    nbytesRead++;

    // Unroll the loop.
    if ((curChar & 1) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 2) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 4) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 8) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 16) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 32) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 64) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if (z->info == MAXVALUE) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
    if ((curChar & 128) != 0) {
      z = z->right;
    } else {
      z = z->left;
    }
    if (z->info > -1) {
      if ( z->info == MAXVALUE ) break;
      cHout[k++] = (char)z->info;
      if (k >= outputBufferSize) {
	return -1;
      }
      z = _globalRoot;
    }
  }

  int nbytes = k;
  return nbytes;
}


/*
int main_FOR_SIMPLE_TESTING_HuffCoder2(int argc, char** argv) {

  HuffCoder2* huffCoder = new HuffCoder2();
  delete huffCoder;

  return 0;
}
*/
