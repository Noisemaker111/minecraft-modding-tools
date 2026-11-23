package mmt.standin.java.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * java.util.List members older Javas lack, from the JDK's documented contract.
 *
 * Java 9's List.of and Java 10's List.copyOf: "unmodifiable lists ... disallow null elements. Attempts to create them with
 * null elements result in NullPointerException"; elements in the order given.
 * Java 21's SequencedCollection methods, as List's own default methods are specified: getFirst() "this.get(0)",
 * getLast() "this.get(this.size() - 1)", both throwing NoSuchElementException when empty; removeFirst() "this.remove(0)",
 * removeLast() "this.remove(this.size() - 1)", the same; addFirst(e) "this.add(0, e)", addLast(e) "this.add(e)";
 * reversed() "a reverse-ordered view of this collection", which writes through to it.
 */
public final class List {

    private List() {}

    public static <E> java.util.List<E> of() {
        return unmodifiable(new Object[] {  });
    }

    public static <E> java.util.List<E> of(E e1) {
        return unmodifiable(new Object[] { e1 });
    }

    public static <E> java.util.List<E> of(E e1, E e2) {
        return unmodifiable(new Object[] { e1, e2 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3) {
        return unmodifiable(new Object[] { e1, e2, e3 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4) {
        return unmodifiable(new Object[] { e1, e2, e3, e4 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8, e9 });
    }

    public static <E> java.util.List<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8, e9, e10 });
    }

    @SafeVarargs
    public static <E> java.util.List<E> of(E... elements) {
        return unmodifiable(elements.clone());
    }

    public static <E> java.util.List<E> copyOf(Collection<? extends E> coll) {
        return unmodifiable(coll.toArray());
    }

    @SuppressWarnings("unchecked")
    static <E> java.util.List<E> unmodifiable(Object[] elements) {
        for (Object e : elements) {
            Objects.requireNonNull(e);
        }
        return Collections.unmodifiableList((java.util.List<E>) new ArrayList<>(Arrays.asList(elements)));
    }

    public static <E> E getFirst(java.util.List<E> self) {
        if (self.isEmpty()) {
            throw new NoSuchElementException();
        }
        return self.get(0);
    }

    public static <E> E getLast(java.util.List<E> self) {
        if (self.isEmpty()) {
            throw new NoSuchElementException();
        }
        return self.get(self.size() - 1);
    }

    public static <E> E removeFirst(java.util.List<E> self) {
        if (self.isEmpty()) {
            throw new NoSuchElementException();
        }
        return self.remove(0);
    }

    public static <E> E removeLast(java.util.List<E> self) {
        if (self.isEmpty()) {
            throw new NoSuchElementException();
        }
        return self.remove(self.size() - 1);
    }

    public static <E> void addFirst(java.util.List<E> self, E e) {
        self.add(0, e);
    }

    public static <E> void addLast(java.util.List<E> self, E e) {
        self.add(e);
    }

    public static <E> java.util.List<E> reversed(java.util.List<E> self) {
        return new Reversed<>(self);
    }

    /** The view reversed() returns: index i of the view is index size() - 1 - i of the list, reads and writes alike. */
    static final class Reversed<E> extends java.util.AbstractList<E> {

        final java.util.List<E> base;

        Reversed(java.util.List<E> base) {
            this.base = base;
        }

        @Override
        public E get(int i) {
            return base.get(base.size() - 1 - mmt.standin.java.util.Objects.checkIndex(i, size()));
        }

        @Override
        public E set(int i, E e) {
            return base.set(base.size() - 1 - mmt.standin.java.util.Objects.checkIndex(i, size()), e);
        }

        @Override
        public void add(int i, E e) {
            if (i < 0 || i > size()) {
                throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + size());
            }
            base.add(base.size() - i, e);
        }

        @Override
        public E remove(int i) {
            return base.remove(base.size() - 1 - mmt.standin.java.util.Objects.checkIndex(i, size()));
        }

        @Override
        public int size() {
            return base.size();
        }
    }

}
