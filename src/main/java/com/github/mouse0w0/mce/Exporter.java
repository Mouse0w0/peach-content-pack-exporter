package com.github.mouse0w0.mce;

import com.github.mouse0w0.mce.data.ContentPackMetadata;
import com.github.mouse0w0.mce.data.ItemData;
import com.github.mouse0w0.mce.data.OreDictData;
import com.github.mouse0w0.mce.data.OreDictEntry;
import com.github.mouse0w0.mce.renderer.FrameBuffer;
import com.github.mouse0w0.mce.renderer.Renderer;
import com.github.mouse0w0.mce.util.FileUtils;
import com.github.mouse0w0.mce.util.JsonUtils;
import com.github.mouse0w0.mce.util.MinecraftUtils;
import com.github.mouse0w0.mce.util.ZipUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.resources.Language;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.client.resource.VanillaResourceType;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.oredict.OreDictionary;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Exporter implements Runnable {

    private final String namespace;
    private final ModContainer modContainer;
    private final Path output;
    private final Path outputFile;

    public Exporter(String namespace, Path outputFile) {
        this.namespace = namespace;
        this.modContainer = Loader.instance().getIndexedModList().get(namespace);
        this.outputFile = outputFile;
        this.output = Paths.get(".export", namespace).toAbsolutePath();
    }

    public Path getOutput() {
        return output;
    }

    public void run() {
        try {
            doExport();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doExport() throws Exception {
        System.out.println("Exporting content from namespace: " + namespace);
        FileUtils.createDirectoriesIfNotExists(output);

        exportMetadata();
        exportItems();
        exportOreDictionary();
        exportLanguage("zh_cn");
        exportLanguage("en_us");

        ZipUtils.zip(outputFile, output);
        System.out.println("Completed export content to " + outputFile);
    }

    private void exportMetadata() throws IOException {
        System.out.println("Exporting content metadata...");
        ContentPackMetadata metadata = new ContentPackMetadata();
        metadata.setId(modContainer.getModId());
        metadata.setVersion(modContainer.getVersion());
        metadata.setMcVersion(Minecraft.getMinecraft().getVersion());
        JsonUtils.writeJson(getOutput().resolve("content.metadata.json"), metadata);
    }

    private void exportItems() throws IOException {
        System.out.println("Exporting items...");
        FrameBuffer frameBuffer = new FrameBuffer(64, 64);
        Renderer.getInstance().startRenderItem(frameBuffer);
        Set<ItemData> itemDataList = new LinkedHashSet<>();
        for (ItemStack itemStack : collectItems()) {
            Item item = itemStack.getItem();
            if (!namespace.equals(item.getRegistryName().getResourceDomain())) continue;
            if (!itemDataList.add(new ItemData(
                    item.getRegistryName().toString(),
                    itemStack.getMetadata(),
                    item.getUnlocalizedName(itemStack),
                    item instanceof ItemBlock))) continue;
            Renderer.getInstance().renderItemToPNG(frameBuffer, itemStack, getOutput().resolve("content/" + namespace + "/image/item/" + item.getRegistryName().getResourcePath() + "_" + itemStack.getMetadata() + ".png"));
        }
        JsonUtils.writeJson(getOutput().resolve("content/" + namespace + "/item.json"), itemDataList);
        Renderer.getInstance().endRenderItem(frameBuffer);
    }

    private void exportOreDictionary() throws IOException {
        System.out.println("Exporting ore dictionary...");
        List<OreDictData> oreDictDataList = new ArrayList<>();
        for (String oreName : OreDictionary.getOreNames()) {
            List<OreDictEntry> oreDictEntryList = OreDictionary.getOres(oreName).parallelStream()
                    .filter(itemStack -> namespace.equals(itemStack.getItem().getRegistryName().getResourceDomain()))
                    .map(itemStack -> new OreDictEntry(itemStack.getItem().getRegistryName().toString(), itemStack.getMetadata()))
                    .collect(Collectors.toList());
            if (oreDictEntryList.isEmpty()) continue;
            oreDictDataList.add(new OreDictData(oreName, oreDictEntryList));
        }
        JsonUtils.writeJson(getOutput().resolve("content/" + namespace + "/oreDictionary.json"), oreDictDataList);
    }

    private void exportLanguage(String language) throws IOException {
        System.out.println("Exporting language: " + language);
        String oldLanguage = MinecraftUtils.getLanguage();
        refreshLanguage(language);
        Properties properties = new Properties();
        for (ItemStack itemStack : collectItems()) {
            Item item = itemStack.getItem();
            if (!namespace.equals(item.getRegistryName().getResourceDomain())) continue;
            properties.setProperty(item.getUnlocalizedName(itemStack), item.getItemStackDisplayName(itemStack));
        }
        Path languageFile = getOutput().resolve("content/" + namespace + "/lang/" + language.toLowerCase() + ".lang");
        FileUtils.createFileIfNotExists(languageFile);
        try (BufferedWriter writer = Files.newBufferedWriter(languageFile)) {
            properties.store(writer, "Generated by MinecraftContentExporter");
        }
        refreshLanguage(oldLanguage);
    }

    private static void refreshLanguage(String locale) {
        Minecraft minecraft = Minecraft.getMinecraft();

        if (minecraft.gameSettings.language.equals(locale)) return;

        minecraft.gameSettings.language = locale;
        minecraft.getLanguageManager().setCurrentLanguage(new Language(locale, "", "", false));
        FMLClientHandler.instance().refreshResources(VanillaResourceType.LANGUAGES);
    }

    private static final Set<String> ignoredCreativeTabs = new HashSet<>(Arrays.asList("search", "inventory", "hotbar"));

    private static List<ItemStack> collectItems() {
        final IBakedModel missingModel = MinecraftUtils.getModelManager().getMissingModel();
        final ItemModelMesher itemModelMesher = Minecraft.getMinecraft().getRenderItem().getItemModelMesher();

        List<ItemStack> itemStacks = new ArrayList<>();

        NonNullList<ItemStack> foundCreativeTabItems = NonNullList.create();
        Set<IBakedModel> foundModel = new HashSet<>();
        for (Item item : Item.REGISTRY) {
            if (item.getHasSubtypes()) {
                foundCreativeTabItems.clear();

                for (CreativeTabs creativeTabs : CreativeTabs.CREATIVE_TAB_ARRAY) {
                    if (ignoredCreativeTabs.contains(creativeTabs.getTabLabel())) continue;
                    item.getSubItems(creativeTabs, foundCreativeTabItems);
                }
                if (!foundCreativeTabItems.isEmpty()) {
                    itemStacks.addAll(foundCreativeTabItems);
                } else {
                    foundModel.clear();
                    for (int metadata = 0; metadata < Short.MAX_VALUE; metadata++) {
                        ItemStack itemStack = new ItemStack(item, 1, metadata);
                        IBakedModel model = itemModelMesher.getItemModel(itemStack);
                        if (model == missingModel) break;
                        if (foundModel.contains(model)) continue;
                        foundModel.add(model);
                        itemStacks.add(itemStack);
                    }
                }
            } else {
                itemStacks.add(new ItemStack(item));
            }
        }
        return itemStacks;
    }
}
