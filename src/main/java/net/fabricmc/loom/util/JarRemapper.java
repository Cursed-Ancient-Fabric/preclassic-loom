/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import net.fabricmc.tinyremapper.*;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.stitch.util.Pair;

public class JarRemapper {
	private final List<IMappingProvider> mappingProviders = new ArrayList<>();
	private final Set<Path> classPath = new HashSet<>();
	private final List<RemapData> remapData = new ArrayList<>();

	public void addMappings(IMappingProvider mappingProvider) {
		mappingProviders.add(mappingProvider);
	}

	public void addToClasspath(Path... paths) {
		classPath.addAll(Arrays.asList(paths));
	}

	public RemapData scheduleRemap(Path input, Path output) {
		RemapData data = new RemapData(input, output);
		remapData.add(data);
		return data;
	}

	public void remap() throws IOException {
		TinyRemapper.Builder remapperBuilder = TinyRemapper.newRemapper();
		mappingProviders.forEach(remapperBuilder::withMappings);

		TinyRemapper remapper = remapperBuilder.build();

		Path[] remapClasspath = classPath.stream()
				.filter(path ->
						remapData.stream().noneMatch(remapData -> remapData.input.equals(path))
				)
				.toArray(Path[]::new);

		remapper.readClassPathAsync(remapClasspath);

		for (RemapData data : remapData) {
			InputTag tag = remapper.createInputTag();
			data.tag = tag;
			remapper.readInputsAsync(tag, data.input);
		}

		List<OutputConsumerPath> outputConsumers = new ArrayList<>();

		for (RemapData data : remapData) {
			OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(data.output).build();
			outputConsumers.add(outputConsumer);

			outputConsumer.addNonClassFiles(data.input, NonClassCopyMode.FIX_META_INF, null);

			data.processAccessWidener(remapper.getRemapper());
			remapper.apply(outputConsumer, data.tag);
		}

		remapper.finish();

		for (OutputConsumerPath outputConsumer : outputConsumers) {
			outputConsumer.close();
		}

		remapData.forEach(RemapData::complete);
	}

	public static class RemapData {
		public final Path input;
		public final Path output;
		BiFunction<RemapData, Remapper, Pair<String, byte[]>> accesWidenerSupplier;
		BiConsumer<RemapData, Pair<String, byte[]>> onComplete;

		private InputTag tag;
		private Pair<String, byte[]> accessWidener;

		public RemapData(Path input, Path output) {
			this.input = input;
			this.output = output;
		}

		public RemapData complete(BiConsumer<RemapData, Pair<String, byte[]>> onComplete) {
			this.onComplete = onComplete;
			return this;
		}

		public RemapData supplyAccessWidener(BiFunction<RemapData, Remapper, Pair<String, byte[]>> beforeFinish) {
			this.accesWidenerSupplier = beforeFinish;
			return this;
		}

		private void complete() {
			if (onComplete != null) {
				onComplete.accept(this, accessWidener);
			}
		}

		private void processAccessWidener(Remapper remapper) {
			if (accesWidenerSupplier != null) {
				accessWidener = accesWidenerSupplier.apply(this, remapper);
			}
		}
	}
}
