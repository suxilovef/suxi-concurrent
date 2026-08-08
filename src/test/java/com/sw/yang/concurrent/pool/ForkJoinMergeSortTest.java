package com.sw.yang.concurrent.pool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * 练习 2：用 RecursiveAction 实现并行归并排序（无返回值任务）
 */
public class ForkJoinMergeSortTest {

    static class MergeSortTask extends RecursiveAction {
        private static final int THRESHOLD = 1000;
        private final int[] arr;
        private final int left;
        private final int right;

        MergeSortTask(int[] arr, int left, int right) {
            this.arr = arr;
            this.left = left;
            this.right = right;
        }

        @Override
        protected void compute() {
            if (right - left <= THRESHOLD) {
                Arrays.sort(arr, left, right + 1); // 小数组直接排序
                return;
            }
            int mid = (left + right) / 2;
            MergeSortTask leftTask = new MergeSortTask(arr, left, mid);
            MergeSortTask rightTask = new MergeSortTask(arr, mid + 1, right);

            // 并行递归排序
            leftTask.fork();
            rightTask.compute();
            leftTask.join();

            // 归并（合并两个有序子数组）
            merge(arr, left, mid, right);
        }

        private void merge(int[] arr, int left, int mid, int right) {
            int[] tmp = Arrays.copyOfRange(arr, left, right + 1);
            int i = 0, j = mid - left + 1;
            int k = left;
            while (i <= mid - left && j < tmp.length) {
                arr[k++] = tmp[i] <= tmp[j] ? tmp[i++] : tmp[j++];
            }
            while (i <= mid - left) arr[k++] = tmp[i++];
            while (j < tmp.length) arr[k++] = tmp[j++];
        }
    }

    @Test
    public void testParallelMergeSort() {
        int size = 1_000_000;
        Random random = new Random(42);
        int[] arr1 = new int[size];
        int[] arr2 = new int[size];
        for (int i = 0; i < size; i++) {
            arr1[i] = random.nextInt(1_000_000);
            arr2[i] = arr1[i];
        }

        // 单线程 Arrays.sort（对比）
        long start = System.currentTimeMillis();
        Arrays.sort(arr1);
        long singleTime = System.currentTimeMillis() - start;

        // ForkJoin 并行归并
        start = System.currentTimeMillis();
        ForkJoinPool pool = new ForkJoinPool();
        pool.invoke(new MergeSortTask(arr2, 0, arr2.length - 1));
        long forkJoinTime = System.currentTimeMillis() - start;

        System.out.println("单线程排序: " + singleTime + "ms");
        System.out.println("ForkJoin排序: " + forkJoinTime + "ms");
        System.out.println(Arrays.equals(arr1, arr2) ? "✅ 排序结果一致" : "❌ 排序结果不一致");
        System.out.println("加速比: " + String.format("%.1f", (double) singleTime / Math.max(forkJoinTime, 1)) + "x");

        pool.shutdown();
    }
}
