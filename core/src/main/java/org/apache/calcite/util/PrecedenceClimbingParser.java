/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.util;

import org.apache.calcite.linq4j.Ord;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Parser that takes a collection of tokens (atoms and operators)
 * and groups them together according to the operators' precedence
 * and associativity.
 */
// 核心作用是根据算符优先机制（Operator Precedence）和结合性（Associativity），将一个扁平的、线性的 Token 序列归约（Reduce）组合成一棵具有层级结构的语法树（AST）。
// 标准的 SQL 解析器（如 JavaCC 产生的解析器）可以很好地处理结构化的 SQL 语句，但当遇到复杂的表达式（例如：a + b * c AND d OR NOT e）时，直接用传统的自顶向下解析会显得很臃肿。
// Calcite 的做法是：先将这一长串表达式当成一个扁平的、交替出现的“原子（Atom）”与“操作符（Operator）”列表读进来，然后交由 PrecedenceClimbingParser 快速进行局部树状化归约。
// 算法核心：算符优先级爬升法（Precedence Climbing）
// 它基于双向链表结构存储 Token。每次循环时，它都会扫描链表，找出当前环境中优先级最高的操作符。
// 如果是一个中缀双目运算符（如 *），它会把左边的 Token 和右边的 Token 剥离出来，封装成一个 Call（表达式节点），然后用这个 Call 原地替换掉原来的三个 Token。
// 随着循环不断进行，链表长度不断缩短，扁平的列表最终“爬升”并浓缩为一个根节点（即最终的 AST）。
public class PrecedenceClimbingParser {
  // 指向当前 Token 双向链表的头节点。随着归约的进行，首节点会不断发生变化。
  private @Nullable Token first;
  // 指向当前 Token 双向链表的尾节点。
  private @Nullable Token last;

  // 当外部传入一个扁平的、无关联的 List<Token> 集合时，这个构造方法通过一次线性遍历，把这些独立的 Token 按照顺序用指针串联起来，在内存中构建出一个双向链表。
  private PrecedenceClimbingParser(List<Token> tokens) {
    // 用来指向当前节点在循环中的前一个节点。因为在刚开始循环时，第一个节点前面没有任何节点，所以初始化为 null。
    Token p = null;
    for (Token token : tokens) {
      if (p != null) {
        p.next = token;
      } else {
        first = token;
      }
      token.previous = p;
      token.next = null;
      p = token;
    }
    last = p;
  }
  // 主要用于将一个词法分析得到的原始数据、变量名或对象包装成算法能够识别的、统一的 Token 实例。
  // Type.ATOM：强制赋予该 Token “原子数据”的角色身份。在算法的后续扫描中，由于其类型的特殊性，解析器绝对不会把它当作运算符来参与结合，而是会让它静静地躺在双向链表中，作为被周围运算符（如 +、*）吞噬的“原材料”。
  // o：传入的业务数据实体（例如数字 100，或者是字段名 "age"）。
  // -1, -1：（核心重点） 将该节点的左结合优先级（left）和右结合优先级（right）统一死死固定为 -1。
  public Token atom(Object o) {
    return new Token(Type.ATOM, o, -1, -1);
  }
  // 专门用来生产 Call（调用/表达式树枝）节点。
  // 在优先级爬升算法的主循环中，当解析器发现某个操作符（Op）达到了合并条件（比如 * 的优先级大于左右邻居），它就需要把这个操作符和它周围的参数（args）打包。
  // 主解析器会直接调用这个 call(op, args) 方法，将它们“焊接”成一个统一的 Call 节点，然后通过我们前面提到的 $O(1)$ 缝合逻辑，把这个新生成的 Call 节点塞回双向链表中。
  public Call call(Op op, ImmutableList<Token> args) {
    return new Call(op, args);
  }

  // 专门用来创建一个中置双目运算符（INFIX Operator）（如 +, -, *, / 等）
  // 看似只有一行的代码，却包含了优先级爬升算法中最为经典的“奇偶位交叉算法（Odd-Even Tie-Breaking）”。
  // 它仅靠纯数学的算术运算，就完美地将程序员眼中的“优先级（Precedence）”和“结合性（Associativity，左结合或右结合）”这两维复杂的逻辑，无缝压缩并编码成了两个一维的整数：left（左结合力）和 right（右结合力）。
  // 左优先级 (left)：precedence * 2 + (left ? 0 : 1)
  // 右优先级 (right)：precedence * 2 + (left ? 1 : 0)
  // 为什么要 “乘以 2” 还要 “加 0 或 1” ？
  // 在 SQL 或数学中，如果两个运算符的基础优先级（Precedence）完全相同，该先算哪一个？这时候就要靠结合性（Associativity）来打破僵局（Tie-Breaking）：
  // 左结合（Left-associative）：如 1 + 2 + 3，应该从左往右算，等价于 (1 + 2) + 3。
  // 右结合（Right-associative）：如 a = b = c（赋值语句），或者是某些特定的数学幂运算，应该从右往左算，等价于 a = (b = c)。
  // 为了让底层核心算法（highest()）在扫描链表时不需要写任何 if (isLeftAssociative) 这样的特判逻辑，作者在这里做了一个数字编码：
  // 场景 A：当运算符是“左结合”（left = true）时
  //我们将公式代入：
  //
  //左优先级 = precedence * 2 + 0 = 偶数
  //
  //右优先级 = precedence * 2 + 1 = 奇数
  //
  //结果：一个左结合的运算符，其右优先级永远比左优先级大 1（右结合力 > 左结合力）。
  // 场景 B：当运算符是“右结合”（left = false）时
  //我们将公式代入：
  //
  //左优先级 = precedence * 2 + 1 = 奇数
  //
  //右优先级 = precedence * 2 + 0 = 偶数
  //
  //结果：一个右结合的运算符，其左优先级永远比右优先级大 1（左结合力 > 右结合力）。
  // 实例演示：左结合的加法 1 + 2 + 3
  //假设加法 + 的基础优先级 precedence = 1。因为加法是左结合的（left = true）。
  //通过 infix 方法编码后，每一个 + 号都会获得：left = 2, right = 3。
  //
  //现在链表的状态是这样的：
  // [ATOM: 1]  <-  (+号A: L=2, R=3)  ->  [ATOM: 2]  <-  (+号B: L=2, R=3)  ->  [ATOM: 3]
  // 当算法扫描到 +号A 时，它想知道能不能先把 1 + 2 算了。于是它需要越过中间的操作数 2，去和右边的 +号B 拼结合力：
  //
  //+号A 的右结合力 是 3。
  //
  //+号B 的左结合力 是 2。
  //
  //算法一比对：3 > 2（+号A 的右结合力 碾压了 +号B 的左结合力）。
  //于是算法立刻做出决策：+号A 胜出！优先执行 1 + 2 归约坍塌。
  public Op infix(Object o, int precedence, boolean left) {
    return new Op(Type.INFIX, o, precedence * 2 + (left ? 0 : 1),
        precedence * 2 + (left ? 1 : 0));
  }
  // 前置单目运算符在 SQL 或数学表达式中非常常见，比如取负号（如 -x）、正号（如 +x）以及逻辑非（如 NOT valid）。它们有一个共同特征：只对紧跟在其右侧的操作数起作用。
  public Op prefix(Object o, int precedence) {
    return new Op(Type.PREFIX, o, -1, precedence * 2);
  }

  public Op postfix(Object o, int precedence) {
    return new Op(Type.POSTFIX, o, precedence * 2, -1);
  }
  // 专门用来创建特殊多目运算符（Special Operator）的工厂方法。
  // leftPrec * 2：将传入的基础左优先级乘以 2，编码为偶数档，赋值给该操作符的 left 属性。
  // rightPrec * 2：将传入的基础右优先级乘以 2，编码为偶数档，赋值给该操作符的 right 属性。
  // 为什么左、右优先级都要显式传入，并且都要乘以 2？
  // 为什么要乘以 2（* 2）？
  //这是为了与整个类的全局优先级大池子保持步调一致。
  // 在infix 方法中，所有的基础优先级都在内部被统一乘以了 2，拉开成了 2, 4, 6... 这样的偶数档，并把奇数档（+1）留给了结合性微调。
  // 为了让特殊运算符在跟旁边普通的 +、*、= 打架比拼结合力时，能够处于同一个数学数量级上，这里的 leftPrec 和 rightPrec 也必须雷打不动地乘以 2。
  public SpecialOp special(Object o, int leftPrec, int rightPrec,
      Special special) {
    return new SpecialOp(o, leftPrec * 2, rightPrec * 2, special);
  }

  public @Nullable Token parse() {
    partialParse();
    if (first != last) {
      throw new AssertionError("could not find next operator to reduce: "
          + this);
    }
    return first;
  }
  // PrecedenceClimbingParser 解析器的动力引擎与核心状态机
  public void partialParse() {
    for (;;) {
      // 去链表里揪出当前“武力值（结合力）最高”的那个运算符 op。
      // 如果 highest() 返回了 null，意味着当前链表里所有的运算符已经全部被消灭（归约）干净了，剩下的全是静态的 ATOM 或 CALL 节点（通常情况下，解析成功的链表此时应该只剩下一个孤零零的根 CALL 节点）。
      // 此时满足终结条件，方法愉快地 return 退出。
      Op op = highest();
      if (op == null) {
        return;
      }
      final Token t;
      switch (op.type) {
      // 后置单目，如 x!
      case POSTFIX: {
        // 抓取参数：强行索要它左边的邻居 previous 作为唯一的参数。如果左边是空的（如直接输入一个 !），requireNonNull 会抛出异常提示语法错误。
        Token previous = requireNonNull(op.previous, () -> "previous of " + op);
        t = call(op, ImmutableList.of(previous));
        // 调用 replace，把从 previous 到 op 这一段从链表中剜掉，换成 t。新节点 t 的左邻变成 previous.previous，右舍变成 op.next。
        replace(t, previous.previous, op.next);
        break;
      }
      // 前置单目，如 -x
      case PREFIX: {
        // 抓取参数：强行索要它右边的邻居 next 充当参数。
        // 缝合链表：完成 Call 打包后，新节点 t 的左邻保留为 op.previous，右舍越过参数变成 next.next。
        Token next = requireNonNull(op.next, () -> "next of " + op);
        t = call(op, ImmutableList.of(next));
        replace(t, op.previous, next.next);
        break;
      }
      // 中置双目，如 a + b
      case INFIX: {
        // 抓取参数：左右开弓，同时强行索要左邻 previous 和右舍 next 作为两个标准参数。
        // 缝合链表：将这一组【左操作数 $\rightarrow$ 运算符 $\rightarrow$ 右操作数】的经典三点结构彻底拔除，替换为复合节点 t。t 的左邻缝合到 previous.previous，右舍缝合到 next.next。
        Token previous = requireNonNull(op.previous, () -> "previous of " + op);
        Token next = requireNonNull(op.next, () -> "next of " + op);
        t = call(op, ImmutableList.of(previous, next));
        replace(t, previous.previous, next.next);
        break;
      }
      // 高级多目，如 A BETWEEN B AND C
      case SPECIAL: {
        // 权力下放：面对复杂的特殊语法，解析器不再自己抓取参数。它把 op 强转为 SpecialOp，并直接触发上面绑定的 special.apply(this, op) 回调接口。
        Result r = ((SpecialOp) op).special.apply(this, (SpecialOp) op);
        requireNonNull(r, "r");
        replace(r.replacement, r.first.previous, r.last.next);
        break;
      }
      default:
        throw new AssertionError();
      }
      // debug: System.out.println(this);
    }
  }

  @Override public String toString() {
    return Util.commaList(all());
  }

  /** Returns a list of all tokens. */
  public List<Token> all() {
    return new TokenList();
  }

  private void replace(Token t, @Nullable Token previous, @Nullable Token next) {
    t.previous = previous;
    t.next = next;
    if (previous == null) {
      first = t;
    } else {
      previous.next = t;
    }
    if (next == null) {
      last = t;
    } else {
      next.previous = t;
    }
  }
  // 解析器的灵魂核心——最高优先级运算符搜寻算法（Highest Precedence Finder）。
  // 当解析器在不断循环归约表达式时，每一轮都会调用 highest() 方法。
  // 它的唯一使命是：通读当前由左到右的双向链表，揪出那一个“在当前上下文中，高、且两侧结合力优先级最完全就绪、可以立刻执行归约”的运算符（Op）。
  private @Nullable Op highest() {
    // 当前最高势能阈值
    // 初始化为 -1。这是因为操作数（ATOM）和已归约节点（CALL）的优先级全是 -1。将阈值设为 -1，可以保证只要链表里存在任何一个合法的操作符（优先级 $\ge 0$），都能立刻通过后续的条件打破僵局。
    int p = -1;
    // 用于记录当前找到的最强运算符对象，若链表已全部坍塌完毕（只剩一个大 CALL 或 ATOM），则返回 null。
    Op highest = null;
    for (Token t = first; t != null; t = t.next) {
      // 条件一：势能碾压条件
      // 当前 Token 的左优先级或右优先级，必须严格大于目前已经记录的最高优先级 p。
      // 如果 t 是一个 ATOM 或 CALL，它的 left 和 right 全是 -1。由于 p 至少是 -1，这个条件永远为 false。这就实现了天然、高能地直接过滤掉所有非操作符节点。
      if ((t.left > p || t.right > p)
          // 条件二：左邻结合力切磋
          // 如果当前操作符有左结合力（t.left >= 0），那么它的左结合力，必须大于等于它左边邻居的右结合力（通过辅助方法 prevRight 跨过操作数去抓取左边的上一个运算符）
          && (t.left < 0 || t.left >= prevRight(t.previous))
          // 条件三：右舍结合力切磋
          // 如果当前操作符有右结合力（t.right >= 0），那么它的右结合力，必须严格大于等于它右边邻居的左结合力（通过辅助方法 nextLeft 跨过操作数去抓取右边的下一个运算符）。
          && (t.right < 0 || t.right >= nextLeft(t.next))) {
        p = Math.max(t.left, t.right);
        highest = (Op) t;
      }
    }
    return highest;
  }

  /** Returns the right precedence of the preceding operator token. */
  // 从当前位置开始向左逆向侦察，跨过所有静态的数据节点，帮当前运算符找出它“左边第一个同行（运算符）”的右结合力（Right Precedence）是多少。
  private static int prevRight(@Nullable Token token) {
    for (; token != null; token = token.previous) {
      if (token.type == Type.POSTFIX) {
        return Integer.MAX_VALUE;
      }
      if (token.right >= 0) {
        return token.right;
      }
    }
    return -1;
  }

  /** Returns the left precedence of the following operator token. */
  // 从当前位置向右顺向侦察，穿透中间的操作数，帮当前运算符找出“右边第一个同行（运算符）”的左结合力（Left Precedence）是多少。
  private static int nextLeft(@Nullable Token token) {
    for (; token != null; token = token.next) {
      if (token.type == Type.PREFIX) {
        return Integer.MAX_VALUE;
      }
      if (token.left >= 0) {
        return token.left;
      }
    }
    return -1;
  }

  public String print(Token token) {
    return token.toString();
  }

  public PrecedenceClimbingParser copy(int start, Predicate<Token> predicate) {
    final List<Token> tokens = new ArrayList<>();
    for (Token token : Util.skip(all(), start)) {
      if (predicate.test(token)) {
        break;
      }
      tokens.add(token.copy());
    }
    return new PrecedenceClimbingParser(tokens);
  }

  /** Token type. */
  // PrecedenceClimbingParser 算法在运行过程中能够识别和处理的所有 Token 角色身份。
  public enum Type {
    // 阵营一：静态数据（操作数）
    ATOM, // 原子节点。最基础的、不可再拆分的数据单元（操作数），常见实例：数字 42、字段名 age、字符串 'USA'。算法行为：它的优先级是固定值 -1。算法在扫描操作符时，会自动跳过 ATOM，绝不把它们当作运算符处理。
    CALL, // 运算结果/局部语法树。已经运算完毕、完成了“合并归约”的复合语法树枝。常见实例：比如 a + b 被算法合并后，就会坍塌成一个 CALL 节点，内部包裹着操作符 + 和参数 [a, b]。算法行为：在算法眼里，一个 CALL 节点的地位和 ATOM 完全等价。它的优先级也是 -1，意味着它已经变成了一个整体，作为一个新的、更高级的“操作数”，静静等待被更外层的其他操作符（如层级更低的 *）吞噬。
    // 阵营二：标准运算符（单目/双目）
    PREFIX, // 前置单目操作符。出现在操作数左侧的单目运算符。常见实例：正负号（如 -x）、逻辑非（如 NOT flag）。
    INFIX, // 中置双目操作符。夹在两个操作数正中间的标准双目运算符。算术加减乘除（+, -, *, /）、比较运算（>, =, <）。
    POSTFIX, // 后置单目操作符。出现在操作数右侧的单目运算符。常见实例：数学中的阶乘（如 n!）、SQL 中的判空（如 expr IS NULL）。
    // 阵营三：高级复合运算符（多目）
    SPECIAL // 特殊多目操作符。无法用简单的“左/右”规则生搬硬套的、拥有复杂语法结构的非标运算符。SQL 中的区间判断（A BETWEEN B AND C）或条件分支（CASE WHEN ... THEN ... END）。
  }

  /** A token: either an atom, a call to an operator with arguments,
   * or an unmatched operator. */
  // Token 是 PrecedenceClimbingParser 的静态内部类，也是整个解析器赖以生存的最基础数据结构模型（Node）。
  // 一个 Token 具有三重身份：它既可以是一个最基础的操作数（atom），可以是一个携带了参数的运算调用节点（call），也可以是一个等待被匹配合并的孤立运算符（unmatched operator）。
  public static class Token {
    // 指向当前 Token 在双向链表中的前驱节点（左侧相邻节点）
    // 设计意图：当算法选中某一个运算符时，需要通过 .previous 快速、就地（以 $O(1)$ 的时间复杂度）抓取它左边的操作数。可为 null（代表当前是链表头）
    @Nullable Token previous;
    // 指向当前 Token 在双向链表中的后继节点（右侧相邻节点）
    // 同理，用于让运算符快速、就地抓取它右边的操作数。可为 null（代表当前是链表尾）。
    @Nullable Token next;
    // 标识该 Token 的具体类型标签。
    public final Type type;
    // 该节点内部包裹的核心业务数据、对象或符号。
    // 如果它是 ATOM，o 可能是具体的数字 10 或字段名 age；如果它是 INFIX，o 则是运算符文本（如 "+", ">="）。
    // 带有 @Nullable 注解，因为当它是 CALL 类型（已结合的调用树）时，该值通常为 null（其内容转由子类 Call 的属性来表达）。
    public final @Nullable Object o;
    // 当前 Token 的左侧结合优先级（Left Precedence）
    // 设计意图：数字越大代表结合力越强。算法会利用这个数字与左侧邻居的右优先级进行切磋，以此决定此运算符是否应该优先和左侧的操作数结合。
    final int left;
    // 当前 Token 的右侧结合优先级（Right Precedence）。
    // 数字越大代表对右侧节点的吸引力越强。用于决定此运算符是否应该优先和右侧的操作数结合。
    final int right;

    Token(Type type, @Nullable Object o, int left, int right) {
      this.type = type;
      this.o = o;
      this.left = left;
      this.right = right;
    }

    /**
     * Returns {@code o}.
     *
     * @return o
     */
    public @Nullable Object o() {
      return o;
    }

    @Override public String toString() {
      return String.valueOf(o);
    }

    protected StringBuilder print(StringBuilder b) {
      return b.append(o);
    }

    public Token copy() {
      return new Token(type, o, left, right);
    }
  }

  /** An operator token. */
  // 在表达式解析中，操作数（Atom）是静态的数据实体，而操作符（Op）则是驱动整个双向链表向内“坍塌、归约”的能量核心。通过继承关系，Calcite 把普通运算符和具有复杂业务逻辑的特殊运算符做到了清晰的隔离。
  // Op 继承自 Token。它代表 SQL 中最常见的普通运算符，例如算术运算符（+, -, *, /）、比较运算符（>, =, <）以及逻辑运算符（AND, OR）。它们要么是单目（前置/后置），要么是双目（中置）。
  public static class Op extends Token {
    Op(Type type, Object o, int left, int right) {
      super(type, o, left, right);
    }

    @Override public Object o() {
      return castNonNull(super.o());
    }

    @Override public Token copy() {
      return new Op(type, o(), left, right);
    }
  }

  /** An token corresponding to a special operator. */
  // SpecialOp 继承自 Op。它用来代表 SQL 中那些无法单纯用“左/右”单双目规则解释的、更高级的复合操作符（例如：BETWEEN ... AND ...，或者 IN (x, y, z)）。
  public static class SpecialOp extends Op {
    public final Special special;

    SpecialOp(Object o, int left, int right, Special special) {
      super(Type.SPECIAL, o, left, right);
      this.special = special;
    }

    @Override public Token copy() {
      return new SpecialOp(o(), left, right, special);
    }
  }


  /** A token that is a call to an operator with arguments. */
  // 当算法在双向链表中发现一个操作符达到了归约条件时，就会把这个操作符和它所需的参数全部剥离出来，塞进一个 Call 节点中。这个节点本质上就是一棵局部抽象语法树（AST Subtree）：它以操作符为根，参数为子节点。
  public static class Call extends Token {
    // 指向当前调用所对应的核心操作符。
    // 通过持有它，Call 节点随时能知道自己是什么运算（例如是一个 + 运算，还是一个 BETWEEN 运算），也保留了该操作符最初的优先级元数据。
    public final Op op;
    // 存放该操作符所绑定到的参数列表（操作数）
    public final ImmutableList<Token> args;

    Call(Op op, ImmutableList<Token> args) {
      super(Type.CALL, null, -1, -1);
      this.op = op;
      this.args = args;
    }

    @Override public Token copy() {
      return new Call(op, args);
    }

    @Override public String toString() {
      return print(new StringBuilder()).toString();
    }

    @Override protected StringBuilder print(StringBuilder b) {
      switch (op.type) {
      case PREFIX:
        b.append('(');
        printOp(b, false, true);
        args.get(0).print(b);
        return b.append(')');
      case POSTFIX:
        b.append('(');
        args.get(0).print(b);
        return printOp(b, true, false).append(')');
      case INFIX:
        b.append('(');
        args.get(0).print(b);
        printOp(b, true, true);
        args.get(1).print(b);
        return b.append(')');
      case SPECIAL:
        printOp(b, false, false)
            .append('(');
        for (Ord<Token> arg : Ord.zip(args)) {
          if (arg.i > 0) {
            b.append(", ");
          }
          arg.e.print(b);
        }
        return b.append(')');
      default:
        throw new AssertionError();
      }
    }

    private StringBuilder printOp(StringBuilder b, boolean leftSpace,
        boolean rightSpace) {
      String s = String.valueOf(op.o);
      if (leftSpace) {
        b.append(' ');
      }
      b.append(s);
      if (rightSpace) {
        b.append(' ');
      }
      return b;
    }
  }

  /** Callback defining the behavior of a special function. */
  // 赋予了整个优先级解析器动态处理非标语法、复杂多目运算符（如 SQL 中的 BETWEEN A AND B，CASE WHEN...，IN (x, y)）的能力。
  // 普通的优先级爬升算法非常擅长处理具有固定参数的单目（PREFIX/POSTFIX）和双目（INFIX）运算符。因为这些运算符只要一就位，就知道百分之百该向左抓一个节点、或者向右抓一个节点。
  // 但是，SQL 中存在很多“奇形怪状”的复合语法。例如：$$expr1 \text{ BETWEEN } expr2 \text{ AND } expr3$$
  // 当解析器扫描到 BETWEEN 这个操作符时，单靠通用的“左右看一眼”算法，根本无法知道该往右抓多少个 Token、什么时候遇到 AND、以及怎么把这三个独立的表达式（expr1, expr2, expr3）安全地合拢到一个调用中。
  // 当主体算法遇到类型为 Type.SPECIAL 的操作符时，它会立刻暂停通用的归约流程，转而调用这个操作符绑定的 Special 接口实现的 apply 方法，把控制权彻底交出去。
  public interface Special {
    /** Given an occurrence of this operator, identifies the range of tokens to
     * be collapsed into a call of this operator, and the arguments to that
     * call. */
    Result apply(PrecedenceClimbingParser parser, SpecialOp op);
  }

  /** Result of a call to {@link Special#apply}. */
  // 专门作为 Special.apply 回调方法的返回值
  // 如果把特殊操作符的回调函数比作一个“切除并重组链表”的微型手术，那么 Result 对象就是这个手术完成后交给主解析器的“手术报告兼缝合指南”。
  public static class Result {
    // 待坍塌区间中，最左侧（第一个）的 Token 节点。
    // 通常情况：对于中置的特殊操作符（如 A BETWEEN B AND C），first 通常就是左边的操作数 A。
    final Token first;
    // 待坍塌区间中，最右侧（最后一个）的 Token 节点。
    // 通常情况：在 A BETWEEN B AND C 的例子中，last 就是最右侧完成解析的操作数 C。
    final Token last;
    // 物理含义：一个全新构造的、类型为 Type.CALL 的 Token 节点。
    // 内部构造：它的内部已经将本次手术中剥离出来的所有有效参数（如 [A, B, C]）打包成了 ImmutableList<Token> 塞进了 args 中。
    final Token replacement;

    public Result(Token first, Token last, Token replacement) {
      this.first = first;
      this.last = last;
      this.replacement = replacement;
    }
  }

  /** Fluent helper to build a parser containing a list of tokens. */
  // Builder 是一个典型的建造者模式（Builder Pattern）实现，并且采用了链式调用（Fluent API）的设计风格。
  public static class Builder {
    // 用来按顺序收集用户推入的所有 Token。在最终调用 build() 时，这个列表会被转化为不可变列表送入解析器。
    final List<Token> tokens = new ArrayList<>();
    // 这是一个非常巧妙的“哑对象（Dummy Object）”或内部工具人。
    private final PrecedenceClimbingParser dummy =
        new PrecedenceClimbingParser(ImmutableList.of());

    private Builder add(Token t) {
      tokens.add(t);
      return this;
    }

    public Builder atom(Object o) {
      return add(dummy.atom(o));
    }

    public Builder call(Op op, Token arg0, Token arg1) {
      return add(dummy.call(op, ImmutableList.of(arg0, arg1)));
    }

    public Builder infix(Object o, int precedence, boolean left) {
      return add(dummy.infix(o, precedence, left));
    }

    public Builder prefix(Object o, int precedence) {
      return add(dummy.prefix(o, precedence));
    }

    public Builder postfix(Object o, int precedence) {
      return add(dummy.postfix(o, precedence));
    }

    public Builder special(Object o, int leftPrec, int rightPrec,
        Special special) {
      return add(dummy.special(o, leftPrec, rightPrec, special));
    }

    public PrecedenceClimbingParser build() {
      return new PrecedenceClimbingParser(tokens);
    }
  }

  /** List view onto the tokens in a parser. The view is semi-mutable; it
   * supports {@link List#remove(int)} but not {@link List#set} or
   * {@link List#add}. */
  private class TokenList extends AbstractList<Token> {
    @Override public Token get(int index) {
      for (Token t = first; t != null; t = t.next) {
        if (index-- == 0) {
          return t;
        }
      }
      throw new IndexOutOfBoundsException();
    }

    @Override public int size() {
      int n = 0;
      for (Token t = first; t != null; t = t.next) {
        ++n;
      }
      return n;
    }

    @Override public Token remove(int index) {
      Token t = get(index);
      if (t.previous == null) {
        first = t.next;
      } else {
        t.previous.next = t.next;
      }
      if (t.next == null) {
        last = t.previous;
      } else {
        t.next.previous = t.previous;
      }
      return t;
    }

    @Override public Token set(int index, Token element) {
      final Token t = get(index);
      element.previous = t.previous;
      if (t.previous == null) {
        first = element;
      } else {
        t.previous.next = element;
      }
      element.next = t.next;
      if (t.next == null) {
        last = element;
      } else {
        t.next.previous = element;
      }
      return t;
    }
  }
}
